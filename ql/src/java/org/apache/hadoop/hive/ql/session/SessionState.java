/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.session;
import static org.apache.hadoop.hive.metastore.Warehouse.DEFAULT_DATABASE_NAME;
import static org.apache.hadoop.hive.shims.HadoopShims.USER_ID;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.AccessController;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.common.JavaUtils;
import org.apache.hadoop.hive.common.io.SessionStream;
import org.apache.hadoop.hive.common.log.ProgressMonitor;
import org.apache.hadoop.hive.common.type.Timestamp;
import org.apache.hadoop.hive.common.type.TimestampTZ;
import org.apache.hadoop.hive.common.type.TimestampTZUtil;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.conf.HiveConfUtil;
import org.apache.hadoop.hive.metastore.ObjectStore;
import org.apache.hadoop.hive.metastore.PersistenceManagerProvider;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.cache.CachedStore;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;
import org.apache.hadoop.hive.ql.QueryState;
import org.apache.hadoop.hive.ql.cleanup.CleanupService;
import org.apache.hadoop.hive.ql.MapRedStats;
import org.apache.hadoop.hive.ql.cleanup.SyncCleanupService;
import org.apache.hadoop.hive.ql.exec.AddToClassPathAction;
import org.apache.hadoop.hive.ql.exec.FunctionInfo;
import org.apache.hadoop.hive.ql.exec.Registry;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.tez.TezSessionPoolManager;
import org.apache.hadoop.hive.ql.exec.tez.TezSessionState;
import org.apache.hadoop.hive.ql.history.HiveHistory;
import org.apache.hadoop.hive.ql.history.HiveHistoryImpl;
import org.apache.hadoop.hive.ql.history.HiveHistoryProxyHandler;
import org.apache.hadoop.hive.ql.lockmgr.HiveTxnManager;
import org.apache.hadoop.hive.ql.lockmgr.LockException;
import org.apache.hadoop.hive.ql.lockmgr.TxnManagerFactory;
import org.apache.hadoop.hive.ql.log.PerfLogger;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.HiveUtils;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.metadata.TempTable;
import org.apache.hadoop.hive.ql.parse.SemanticAnalyzer;
import org.apache.hadoop.hive.ql.security.HiveAuthenticationProvider;
import org.apache.hadoop.hive.ql.security.authorization.HiveAuthorizationProvider;
import org.apache.hadoop.hive.ql.security.authorization.plugin.AuthorizationMetaStoreFilterHook;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthorizer;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthorizerFactory;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthzSessionContext;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthzSessionContext.CLIENT_TYPE;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveMetastoreClientFactoryImpl;
import org.apache.hadoop.hive.ql.util.ResourceDownloader;
import org.apache.hadoop.hive.shims.HadoopShims;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.hive.shims.Utils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hive.common.util.ShutdownHookManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

/**
 * SessionState encapsulates common data associated with a session.
 *
 * Also provides support for a thread static session object that can be accessed
 * from any point in the code to interact with the user and to retrieve
 * configuration information
 */
public class SessionState implements ISessionAuthState {
  private static final Logger LOG = LoggerFactory.getLogger(SessionState.class);

  public static final String TMP_PREFIX = "_tmp_space.db";
  private static final String LOCAL_SESSION_PATH_KEY = "_hive.local.session.path";
  private static final String HDFS_SESSION_PATH_KEY = "_hive.hdfs.session.path";
  private static final String TMP_TABLE_SPACE_KEY = "_hive.tmp_table_space";
  static final String LOCK_FILE_NAME = "inuse.lck";
  static final String INFO_FILE_NAME = "inuse.info";

  /**
   * Concurrent since SessionState is often propagated to workers in thread pools
   */
  private final Map<String, Map<String, Table>> tempTables = new ConcurrentHashMap<>();
  private final Map<String, Map<String, ColumnStatisticsObj>> tempTableColStats =
      new ConcurrentHashMap<>();
  private final Map<String, TempTable> tempPartitions =
      new ConcurrentHashMap<>();

  // Prepared statement plans
  private final Map<String, SemanticAnalyzer> preparePlanMap = new ConcurrentHashMap<>();

  protected ClassLoader parentLoader;

  // Session-scope compile lock.
  private final ReentrantLock compileLock = new ReentrantLock(true);

  /**
   * current configuration.
   */
  private HiveConf sessionConf;

  /**
   * silent mode.
   */
  protected boolean isSilent;

  /**
   * silent mode.
   */
  protected boolean isQtestLogging;

  /**
   * verbose mode
   */
  protected boolean isVerbose;

  /**
   * The flag to indicate if the session serves the queries from HiveServer2 or not.
   */
  private boolean isHiveServerQuery = false;

  /**
   * The flag to indicate if the session using thrift jdbc binary serde or not.
   */
  private boolean isUsingThriftJDBCBinarySerDe = false;

  /**
   * The flag to indicate if the session already started so we can skip the init
   */
  private AtomicBoolean isStarted = new AtomicBoolean(false);
  /*
   * HiveHistory Object
   */
  protected HiveHistory hiveHist;

  /**
   * Streams to read/write from.
   */
  public InputStream in;
  public SessionStream out;
  public SessionStream info;
  public SessionStream err;
  /**
   * Standard output from any child process(es).
   */
  public PrintStream childOut;
  /**
   * Error output from any child process(es).
   */
  public PrintStream childErr;

  /**
   * Temporary file name used to store results of non-Hive commands (e.g., set, dfs)
   * and HiveServer.fetch*() function will read results from this file
   */
  protected File tmpOutputFile;

  /**
   * Temporary file name used to store error output of executing non-Hive commands (e.g., set, dfs)
   */
  protected File tmpErrOutputFile;

  private String lastCommand;

  private HiveAuthorizationProvider authorizer;

  private HiveAuthorizer authorizerV2;
  private volatile ProgressMonitor progressMonitor;

  private String hiveServer2HostName;

  private KillQuery killQuery;

  public enum AuthorizationMode{V1, V2};

  private HiveAuthenticationProvider authenticator;

  private CreateTableAutomaticGrant createTableGrants;

  private Map<String, MapRedStats> mapRedStats;

  private Map<String, String> hiveVariables;

  // A mapping from a hadoop job ID to the stack traces collected from the map reduce task logs
  private Map<String, List<List<String>>> stackTraces;

  // This mapping collects all the configuration variables which have been set by the user
  // explicitly, either via SET in the CLI, the hiveconf option, or a System property.
  // It is a mapping from the variable name to its value.  Note that if a user repeatedly
  // changes the value of a variable, the corresponding change will be made in this mapping.
  private Map<String, String> overriddenConfigurations;
  private Map<String, String> overriddenMetaConfigurations;

  private Map<String, List<String>> localMapRedErrors;

  private TezSessionState tezSessionState;

  private String currentDatabase;

  private String currentCatalog;

  private static final String CONFIG_AUTHZ_SETTINGS_APPLIED_MARKER =
      "_hive.ss.authz.settings.applied.marker";

  private String userIpAddress;

  private final Map<Class, Object> dynamicVars = new HashMap<>();

  /**
   * Gets information about HDFS encryption
   */
  private Map<URI, HadoopShims.HdfsEncryptionShim> hdfsEncryptionShims = Maps.newHashMap();

  private final String userName;

  /**
   *  scratch path to use for all non-local (ie. hdfs) file system tmp folders
   *  @return Path for Scratch path for the current session
   */
  private Path hdfsSessionPath;

  private FSDataOutputStream hdfsSessionPathLockFile = null;

  /**
   * sub dir of hdfs session path. used to keep tmp tables
   * @return Path for temporary tables created by the current session
   */
  private Path hdfsTmpTableSpace;

  /**
   *  scratch directory to use for local file system tmp folders
   *  @return Path for local scratch directory for current session
   */
  private Path localSessionPath;

  private String hdfsScratchDirURIString;

  /**
   * Next value to use in naming a temporary table created by an insert...values statement
   */
  private int nextValueTempTableSuffix = 1;

  /**
   * Transaction manager to use for this session.  This is instantiated lazily by
   * {@link #initTxnMgr(org.apache.hadoop.hive.conf.HiveConf)}
   */
  private HiveTxnManager txnMgr = null;

  /**
   * store the jars loaded last time
   */
  private final Set<String> preReloadableAuxJars = new HashSet<String>();

  private final Registry registry;

  private final CleanupService cleanupService;

  /**
   * Used to cache functions in use for a query, during query planning
   * and is later used for function usage authorization.
   */
  private final Map<String, FunctionInfo> currentFunctionsInUse = new HashMap<>();

  /**
   * CURRENT_TIMESTAMP value for query
   */
  private Instant queryCurrentTimestamp;

  private final ResourceMaps resourceMaps;

  private final ResourceDownloader resourceDownloader;

  private List<String> forwardedAddresses;

  private String atsDomainId;

  private List<Closeable> cleanupItems = new LinkedList<Closeable>();

  private Hive hiveDb;
  private final Map<String, QueryState> queryStateMap = new HashMap<>();

  /**
   * Marker flag to indicate that the current SessionState (and Driver) instance is used for executing compaction queries only.
   * It is required to exclude compaction related queries from all Ranger policies that would otherwise apply.
   */
  private boolean compaction = false;

  // A thread-safe set to hold all active session states
  private static final Set<SessionState> sessionStates = ConcurrentHashMap.newKeySet();

  // Static block to add a single shutdown hook to clean up all session states
  static {
    ShutdownHookManager.addShutdownHook(SessionState::cleanUpAllSessionStates);
  }

  public QueryState getQueryState(String queryId) {
    return queryStateMap.get(queryId);
  }

  public void addQueryState(String queryId, QueryState queryState) {
    queryStateMap.put(queryId, queryState);
  }

  public void removeQueryState(String queryId) {
    queryStateMap.remove(queryId);
  }

  @Override
  public HiveConf getConf() {
    return sessionConf;
  }

  public void setConf(HiveConf conf) {
    if (hiveDb != null) {
      hiveDb.setConf(conf);
    }
    this.sessionConf = conf;
  }

  public File getTmpOutputFile() {
    return tmpOutputFile;
  }

  public void setTmpOutputFile(File f) {
    tmpOutputFile = f;
  }

  public File getTmpErrOutputFile() {
    return tmpErrOutputFile;
  }

  public void setTmpErrOutputFile(File tmpErrOutputFile) {
    this.tmpErrOutputFile = tmpErrOutputFile;
  }

  public void deleteTmpOutputFile() {
    FileUtils.deleteTmpFile(tmpOutputFile);
  }

  public void deleteTmpErrOutputFile() {
    FileUtils.deleteTmpFile(tmpErrOutputFile);
  }

  public boolean getIsSilent() {
    if(sessionConf != null) {
      return sessionConf.getBoolVar(HiveConf.ConfVars.HIVE_SESSION_SILENT);
    } else {
      return isSilent;
    }
  }

  public boolean getIsQtestLogging() {
    return isQtestLogging;
  }

  public boolean isHiveServerQuery() {
    return this.isHiveServerQuery;
  }

  public void setIsSilent(boolean isSilent) {
    if(sessionConf != null) {
      sessionConf.setBoolVar(HiveConf.ConfVars.HIVE_SESSION_SILENT, isSilent);
    }
    this.isSilent = isSilent;
  }

  public void setIsQtestLogging(boolean isQtestLogging) {
    this.isQtestLogging = isQtestLogging;
  }

  public ReentrantLock getCompileLock() {
    return compileLock;
  }

  public boolean getIsVerbose() {
    return isVerbose;
  }

  public void setIsVerbose(boolean isVerbose) {
    this.isVerbose = isVerbose;
  }

  public void setIsUsingThriftJDBCBinarySerDe(boolean isUsingThriftJDBCBinarySerDe) {
    this.isUsingThriftJDBCBinarySerDe = isUsingThriftJDBCBinarySerDe;
  }

  public boolean getIsUsingThriftJDBCBinarySerDe() {
    return isUsingThriftJDBCBinarySerDe;
  }

  public void setIsHiveServerQuery(boolean isHiveServerQuery) {
    this.isHiveServerQuery = isHiveServerQuery;
  }

  public boolean isCompaction() {
    return compaction;
  }

  public void setCompaction(boolean compaction) {
    this.compaction = compaction;
  }

  public SessionState(HiveConf conf) {
    this(conf, null);
  }

  public SessionState(HiveConf conf, String userName) {
    this(conf, userName, SyncCleanupService.INSTANCE);
  }

  public SessionState(HiveConf conf, String userName, CleanupService cleanupService) {
    this.sessionConf = conf;
    this.userName = userName;
    this.registry = new Registry(false);
    if (LOG.isDebugEnabled()) {
      LOG.debug("SessionState user: " + userName);
    }
    isSilent = conf.getBoolVar(HiveConf.ConfVars.HIVE_SESSION_SILENT);
    resourceMaps = new ResourceMaps();
    // Must be deterministic order map for consistent q-test output across Java versions
    overriddenConfigurations = new LinkedHashMap<String, String>();
    // if there isn't already a session name, go ahead and create it.
    if (StringUtils.isEmpty(conf.getVar(HiveConf.ConfVars.HIVE_SESSION_ID))) {
      conf.setVar(HiveConf.ConfVars.HIVE_SESSION_ID, makeSessionId());
      getConsole().printInfo("Hive Session ID = " + getSessionId());
    }
    // Using system classloader as the parent. Using thread context
    // classloader as parent can pollute the session. See HIVE-11878
    parentLoader = SessionState.class.getClassLoader();
    // Make sure that each session has its own UDFClassloader. For details see {@link UDFClassLoader}
    AddToClassPathAction addAction = new AddToClassPathAction(
        parentLoader, Collections.emptyList(), true);
    final ClassLoader currentLoader = AccessController.doPrivileged(addAction);
    this.sessionConf.setClassLoader(currentLoader);
    resourceDownloader = new ResourceDownloader(conf,
        HiveConf.getVar(conf, ConfVars.DOWNLOADED_RESOURCES_DIR));
    killQuery = new NullKillQuery();
    this.cleanupService = cleanupService;

    ShimLoader.getHadoopShims().setHadoopSessionContext(String.format(USER_ID, getSessionId(), userName));
  }

  public Map<String, String> getHiveVariables() {
    if (hiveVariables == null) {
      hiveVariables = new HashMap<String, String>();
    }
    return hiveVariables;
  }

  public void setHiveVariables(Map<String, String> hiveVariables) {
    this.hiveVariables = hiveVariables;
  }

  public String getSessionId() {
    return (sessionConf.getVar(HiveConf.ConfVars.HIVE_SESSION_ID));
  }

  public void updateThreadName() {
    final String sessionId = getSessionId();
    final String logPrefix = getConf().getLogIdVar(sessionId);
    final String currThreadName = Thread.currentThread().getName();
    if (!currThreadName.contains(logPrefix)) {
      final String newThreadName = logPrefix + " " + currThreadName;
      LOG.debug("Updating thread name to {}", newThreadName);
      Thread.currentThread().setName(newThreadName);
    }
  }

  public void resetThreadName() {
    final String sessionId = getSessionId();
    final String logPrefix = getConf().getLogIdVar(sessionId);
    final String currThreadName = Thread.currentThread().getName();
    if (currThreadName.contains(logPrefix)) {
      final String[] names = currThreadName.split(logPrefix);
      LOG.debug("Resetting thread name to {}", names[names.length - 1]);
      Thread.currentThread().setName(names[names.length - 1].trim());
    }
  }

  /**
   * Initialize the transaction manager.  This is done lazily to avoid hard wiring one
   * transaction manager at the beginning of the session.
   * @param conf Hive configuration to initialize transaction manager
   * @return transaction manager
   * @throws LockException
   */
  public synchronized HiveTxnManager initTxnMgr(HiveConf conf) throws LockException {
    // Only change txnMgr if the setting has changed
    if (txnMgr != null &&
        !txnMgr.getTxnManagerName().equals(conf.getVar(HiveConf.ConfVars.HIVE_TXN_MANAGER))) {
      txnMgr.closeTxnManager();
      txnMgr = null;
    }

    if (txnMgr == null) {
      txnMgr = TxnManagerFactory.getTxnManagerFactory().getTxnManager(conf);
    }
    return txnMgr;
  }

  /**
   * Get the transaction manager for the current SessionState.
   * Note that the Driver can be initialized with a different txn manager than the SessionState's
   * txn manager (HIVE-17482), and so it is preferable to use the txn manager propagated down from
   * the Driver as opposed to calling this method.
   * @return transaction manager for the current SessionState
   */
  public HiveTxnManager getTxnMgr() {
    return txnMgr;
  }

  /**
   * This only for testing.  It allows to switch the manager before the (test) operation so that
   * it's not coupled to the executing thread.  Since tests run against Derby which often wedges
   * under concurrent access, tests must use a single thead and simulate concurrent access.
   * For example, {@code TestDbTxnManager2}
   * @return previous {@link HiveTxnManager} or null
   */
  @VisibleForTesting
  public HiveTxnManager setTxnMgr(HiveTxnManager mgr) {
    if(!(sessionConf.getBoolVar(ConfVars.HIVE_IN_TEST) || sessionConf.getBoolVar(ConfVars.HIVE_IN_TEZ_TEST))) {
      throw new IllegalStateException("Only for testing!");
    }
    HiveTxnManager tmp = txnMgr;
    txnMgr = mgr;
    return tmp;
  }
  public HadoopShims.HdfsEncryptionShim getHdfsEncryptionShim() throws HiveException {
    try {
      return getHdfsEncryptionShim(FileSystem.get(sessionConf), sessionConf);
    }
    catch(HiveException hiveException) {
      throw hiveException;
    }
    catch(Exception exception) {
      throw new HiveException(exception);
    }
  }

  public HadoopShims.HdfsEncryptionShim getHdfsEncryptionShim(FileSystem fs, HiveConf conf) throws HiveException {

    if (!"hdfs".equals(fs.getUri().getScheme())) {
      LOG.warn("Unable to get hdfs encryption shim, because FileSystem URI schema is not hdfs. Returning null. "
          + "FileSystem URI: " + fs.getUri());
      return null;
    }

    if (conf.getBoolVar(ConfVars.HIVE_HDFS_ENCRYPTION_SHIM_CACHE_ON)) {
      if (!hdfsEncryptionShims.containsKey(fs.getUri())) {
          hdfsEncryptionShims.put(fs.getUri(), getHdfsEncryptionShimInternal(fs));
      }
      return hdfsEncryptionShims.get(fs.getUri());

    } else { // skip the cache
      return getHdfsEncryptionShimInternal(fs);
    }
  }

  private HadoopShims.HdfsEncryptionShim getHdfsEncryptionShimInternal(FileSystem fs) throws HiveException {
    try {
      return ShimLoader.getHadoopShims().createHdfsEncryptionShim(fs, sessionConf);
    } catch (Exception e) {
      throw new HiveException(e);
    }
  }

  // SessionState is not available in runtime and Hive.get().getConf() is not safe to call
  private static class SessionStates {
    private SessionState state;
    private HiveConf conf;
    private void attach(SessionState state) {
      this.state = state;
      attach(state.getConf());
    }
    private void attach(HiveConf conf) {
      this.conf = conf;

      ClassLoader classLoader = conf.getClassLoader();
      if (classLoader != null) {
        Thread.currentThread().setContextClassLoader(classLoader);
      }
    }
  }

  /**
   * Singleton Session object per thread.
   *
   **/
  private static ThreadLocal<SessionStates> tss = new ThreadLocal<SessionStates>() {
    @Override
    protected SessionStates initialValue() {
      return new SessionStates();
    }
  };

  /**
   * start a new session and set it to current session.
   */
  public static SessionState start(HiveConf conf) {
    SessionState ss = new SessionState(conf);
    return start(ss);
  }

  /**
   * Sets the given session state in the thread local var for sessions.
   */
  public static void setCurrentSessionState(SessionState startSs) {
    tss.get().attach(startSs);
  }

  public static void detachSession() {
    tss.remove();
  }

  /**
   * set current session to existing session object if a thread is running
   * multiple sessions - it must call this method with the new session object
   * when switching from one session to another.
   */
  public static SessionState start(SessionState startSs) {
    start(startSs, false, null);

    // Register the session state in the centralized set
    sessionStates.add(startSs);

    return startSs;
  }

  public static void beginStart(SessionState startSs, LogHelper console) {
    start(startSs, true, console);
  }

  public static void endStart(SessionState startSs)
      throws CancellationException, InterruptedException {
    if (startSs.tezSessionState == null) {
      return;
    }
    startSs.tezSessionState.endOpen();
  }

  private static void start(SessionState startSs, boolean isAsync, LogHelper console) {
    // Starting from ORC 2.x, ZSTD is the default compression codec and is implemented using the zstd-jni library.
    // To fallback to the pure Java implementation (Aircompressor), set the appropriate configuration property.
    // TODO: ZSTD with zstd-jni is currently not yet supported in Hive.
    System.setProperty("orc.compression.zstd.impl", "java");
    setCurrentSessionState(startSs);

    if (!startSs.isStarted.compareAndSet(false, true)) {
      return;
    }

    if (startSs.hiveHist == null){
      if (startSs.getConf().getBoolVar(HiveConf.ConfVars.HIVE_SESSION_HISTORY_ENABLED)) {
        startSs.hiveHist = new HiveHistoryImpl(startSs);
      } else {
        // Hive history is disabled, create a no-op proxy
        startSs.hiveHist = HiveHistoryProxyHandler.getNoOpHiveHistoryProxy();
      }
    }

    // Get the following out of the way when you start the session these take a
    // while and should be done when we start up.
    try {
      UserGroupInformation sessionUGI = Utils.getUGI();
      FileSystem.get(startSs.sessionConf);

      // Create scratch dirs for this session
      startSs.createSessionDirs(sessionUGI.getShortUserName());

      // Set temp file containing results to be sent to HiveClient
      if (startSs.getTmpOutputFile() == null) {
        try {
          startSs.setTmpOutputFile(createTempFile(startSs.getConf()));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      // Set temp file containing error output to be sent to client
      if (startSs.getTmpErrOutputFile() == null) {
        try {
          startSs.setTmpErrOutputFile(createTempFile(startSs.getConf()));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // Catch-all due to some exec time dependencies on session state
      // that would cause ClassNoFoundException otherwise
      throw new RuntimeException(e);
    }

    String engine = HiveConf.getVar(startSs.getConf(), HiveConf.ConfVars.HIVE_EXECUTION_ENGINE);

    if (!engine.equals("tez") || startSs.isHiveServerQuery
            || !HiveConf.getBoolVar(startSs.getConf(), ConfVars.HIVE_CLI_TEZ_INITIALIZE_SESSION)) {
      return;
    }

    try {
      if (startSs.tezSessionState == null) {
        startSs.setTezSession(new TezSessionState(startSs.getSessionId(), startSs.sessionConf));
      } else {
        // Only TezTask sets this, and then removes when done, so we don't expect to see it.
        LOG.warn("Tez session was already present in SessionState before start: "
            + startSs.tezSessionState);
      }
      if (startSs.tezSessionState.isOpen()) {
        return;
      }
      if (startSs.tezSessionState.isOpening()) {
        if (!isAsync) {
          startSs.tezSessionState.endOpen();
        }
        return;
      }
      // Neither open nor opening.
      if (!isAsync) {
        startSs.tezSessionState.open();
      } else {
        startSs.tezSessionState.beginOpen(null, console);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Create dirs & session paths for this session:
   * 1. HDFS scratch dir
   * 2. Local scratch dir
   * 3. Local downloaded resource dir
   * 4. HDFS session path
   * 5. hold a lock file in HDFS session dir to indicate the it is in use
   * 6. Local session path
   * 7. HDFS temp table space
   * @param userName
   * @throws IOException
   */
  private void createSessionDirs(String userName) throws IOException {
    HiveConf conf = getConf();
    Path rootHDFSDirPath = createRootHDFSDir(conf);
    // Now create session specific dirs
    String scratchDirPermission = HiveConf.getVar(conf, HiveConf.ConfVars.SCRATCH_DIR_PERMISSION);
    Path path;
    // 1. HDFS scratch dir
    path = new Path(rootHDFSDirPath, userName);
    hdfsScratchDirURIString = path.toUri().toString();
    createPath(conf, path, scratchDirPermission, false, false);
    // 2. Local scratch dir
    path = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.LOCAL_SCRATCH_DIR));
    createPath(conf, path, scratchDirPermission, true, false);
    // 3. Download resources dir
    path = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.DOWNLOADED_RESOURCES_DIR));
    createPath(conf, path, scratchDirPermission, true, false);
    // Finally, create session paths for this session
    // Local & non-local tmp location is configurable. however it is the same across
    // all external file systems
    String sessionId = getSessionId();
    // 4. HDFS session path
    hdfsSessionPath = new Path(hdfsScratchDirURIString, sessionId);
    createPath(conf, hdfsSessionPath, scratchDirPermission, false, true);
    conf.set(HDFS_SESSION_PATH_KEY, hdfsSessionPath.toUri().toString());
    // 5. hold a lock file in HDFS session dir to indicate the it is in use
    if (conf.getBoolVar(HiveConf.ConfVars.HIVE_SCRATCH_DIR_LOCK)) {
      FileSystem fs = hdfsSessionPath.getFileSystem(conf);
      FSDataOutputStream hdfsSessionPathInfoFile = fs.create(new Path(hdfsSessionPath, INFO_FILE_NAME),
          true);
      hdfsSessionPathInfoFile.writeUTF("process: " + ManagementFactory.getRuntimeMXBean().getName()
          +"\n");
      hdfsSessionPathInfoFile.close();
      hdfsSessionPathLockFile = fs.create(new Path(hdfsSessionPath, LOCK_FILE_NAME), true);
    }
    // 6. Local session path
    localSessionPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.LOCAL_SCRATCH_DIR), sessionId);
    createPath(conf, localSessionPath, scratchDirPermission, true, true);
    conf.set(LOCAL_SESSION_PATH_KEY, localSessionPath.toUri().toString());
    // 7. HDFS temp table space
    hdfsTmpTableSpace = new Path(hdfsSessionPath, TMP_PREFIX);
    // This is a sub-dir under the hdfsSessionPath. Will be removed along with that dir.
    // Don't register with deleteOnExit
    createPath(conf, hdfsTmpTableSpace, scratchDirPermission, false, false);
    conf.set(TMP_TABLE_SPACE_KEY, hdfsTmpTableSpace.toUri().toString());

    // _hive.tmp_table_space, _hive.hdfs.session.path, and _hive.local.session.path are respectively
    // saved in hdfsTmpTableSpace, hdfsSessionPath and localSessionPath.  Saving them as conf
    // variables is useful to expose them to end users.  But, end users shouldn't change them.
    // Adding them to restricted list.
    conf.addToRestrictList(
        LOCAL_SESSION_PATH_KEY + "," + HDFS_SESSION_PATH_KEY + "," + TMP_TABLE_SPACE_KEY);
  }

  /**
   * Create the root scratch dir on hdfs (if it doesn't already exist) and make it writable
   * @param conf
   * @return
   * @throws IOException
   */
  private Path createRootHDFSDir(HiveConf conf) throws IOException {
    Path rootHDFSDirPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.SCRATCH_DIR));
    Utilities.ensurePathIsWritable(rootHDFSDirPath, conf);
    return rootHDFSDirPath;
  }

  /**
   * Create a given path if it doesn't exist.
   *
   * @param conf
   * @param path
   * @param permission
   * @param isLocal
   * @param isCleanUp
   * @return
   * @throws IOException
   */
  @VisibleForTesting
  static void createPath(HiveConf conf, Path path, String permission, boolean isLocal,
      boolean isCleanUp) throws IOException {
    FsPermission fsPermission = new FsPermission(permission);
    FileSystem fs;
    if (isLocal) {
      fs = FileSystem.getLocal(conf);
    } else {
      fs = path.getFileSystem(conf);
    }
    if (!fs.exists(path)) {
      if (!fs.mkdirs(path, fsPermission)) {
        throw new IOException("Failed to create directory " + path + " on fs " + fs.getUri());
      }
      String dirType = isLocal ? "local" : "HDFS";
      LOG.info("Created " + dirType + " directory: " + path.toString());
    }
    if (isCleanUp) {
      fs.deleteOnExit(path);
    }
  }

  public String getHdfsScratchDirURIString() {
    return hdfsScratchDirURIString;
  }

  public static Path getLocalSessionPath(Configuration conf) {
    SessionState ss = SessionState.get();
    if (ss == null) {
      String localPathString = conf.get(LOCAL_SESSION_PATH_KEY);
      Preconditions.checkNotNull(localPathString,
          "Conf local session path expected to be non-null");
      return new Path(localPathString);
    }
    Preconditions.checkNotNull(ss.localSessionPath,
        "Local session path expected to be non-null");
    return ss.localSessionPath;
  }

  public static Path getHDFSSessionPath(Configuration conf) {
    SessionState ss = SessionState.get();
    if (ss == null) {
      String sessionPathString = conf.get(HDFS_SESSION_PATH_KEY);
      Preconditions.checkNotNull(sessionPathString,
          "Conf non-local session path expected to be non-null");
      return new Path(sessionPathString);
    }
    Preconditions.checkNotNull(ss.hdfsSessionPath,
       "Non-local session path expected to be non-null");
    return ss.hdfsSessionPath;
  }

  public static Path getTempTableSpace(Configuration conf) {
    SessionState ss = SessionState.get();
    if (ss == null) {
      String tempTablePathString = conf.get(TMP_TABLE_SPACE_KEY);
      Preconditions.checkNotNull(tempTablePathString,
          "Conf temp table path expected to be non-null");
      return new Path(tempTablePathString);
    }
    return ss.getTempTableSpace();
  }

  public Path getTempTableSpace() {
    Preconditions.checkNotNull(this.hdfsTmpTableSpace,
        "Temp table path expected to be non-null");
    return this.hdfsTmpTableSpace;
  }

  public static String generateTempTableLocation(Configuration conf) throws MetaException {
    Path path = new Path(SessionState.getTempTableSpace(conf), UUID.randomUUID().toString());
    path = Warehouse.getDnsPath(path, conf);
    return path.toString();
  }

  @VisibleForTesting
  void releaseSessionLockFile() throws IOException {
    if (hdfsSessionPath != null && hdfsSessionPathLockFile != null) {
      hdfsSessionPathLockFile.close();
    }
  }

  public CleanupService getCleanupService() {
    return cleanupService;
  }

  private void dropSessionPaths(Configuration conf) throws IOException {
    if (hdfsSessionPath != null) {
      if (hdfsSessionPathLockFile != null) {
        try {
          hdfsSessionPathLockFile.close();
        } catch (IOException e) {
          LOG.error("Failed while closing remoteFsSessionLockFile", e);
        }
      }
      dropPathAndUnregisterDeleteOnExit(hdfsSessionPath, conf, false);
    }
    if (localSessionPath != null) {
      dropPathAndUnregisterDeleteOnExit(localSessionPath, conf, true);
    }
    deleteTmpOutputFile();
    deleteTmpErrOutputFile();
  }

  private void dropPathAndUnregisterDeleteOnExit(Path path, Configuration conf, boolean localFs) {
    FileSystem fs = null;
    try {
      if (localFs) {
        fs = FileSystem.getLocal(conf);
      } else {
        fs = path.getFileSystem(conf);
      }
      cleanupService.deleteRecursive(path, fs);
    } catch (IllegalArgumentException | UnsupportedOperationException | IOException e) {
      LOG.error("Failed to delete path at {} on fs with scheme {}", path,
          (fs == null ? "Unknown-null" : fs.getScheme()), e);
    }
  }

  /**
   * Setup authentication and authorization plugins for this session.
   */
  private synchronized void setupAuth() {

    if (authenticator != null) {
      // auth has been initialized
      return;
    }

    try {
      authenticator = HiveUtils.getAuthenticator(sessionConf,
          HiveConf.ConfVars.HIVE_AUTHENTICATOR_MANAGER);
      authenticator.setSessionState(this);

      String clsStr = HiveConf.getVar(sessionConf, HiveConf.ConfVars.HIVE_AUTHORIZATION_MANAGER);
      authorizer = HiveUtils.getAuthorizeProviderManager(sessionConf,
          clsStr, authenticator, true);

      if (authorizer == null) {
        // if it was null, the new (V2) authorization plugin must be specified in
        // config
        HiveAuthorizerFactory authorizerFactory = HiveUtils.getAuthorizerFactory(sessionConf,
            HiveConf.ConfVars.HIVE_AUTHORIZATION_MANAGER);

        HiveAuthzSessionContext.Builder authzContextBuilder = new HiveAuthzSessionContext.Builder();
        authzContextBuilder.setClientType(isHiveServerQuery() ? CLIENT_TYPE.HIVESERVER2
            : CLIENT_TYPE.HIVECLI);
        authzContextBuilder.setSessionString(getSessionId());

        authorizerV2 = authorizerFactory.createHiveAuthorizer(new HiveMetastoreClientFactoryImpl(),
            sessionConf, authenticator, authzContextBuilder.build());
        setAuthorizerV2Config();

      }
      // create the create table grants with new config
      createTableGrants = CreateTableAutomaticGrant.create(sessionConf);

    } catch (HiveException e) {
      LOG.error("Error setting up authorization: " + e.getMessage(), e);
      throw new RuntimeException(e);
    }

    if(LOG.isDebugEnabled()){
      Object authorizationClass = getActiveAuthorizer();
      LOG.debug("Session is using authorization class " + authorizationClass.getClass());
    }
    return;
  }

  private void setAuthorizerV2Config() throws HiveException {
    // avoid processing the same config multiple times, check marker
    if (sessionConf.get(CONFIG_AUTHZ_SETTINGS_APPLIED_MARKER, "").equals(Boolean.TRUE.toString())) {
      return;
    }
    String metastoreHook = sessionConf.getVar(ConfVars.METASTORE_FILTER_HOOK);
    if (!ConfVars.METASTORE_FILTER_HOOK.getDefaultValue().equals(metastoreHook) &&
        !AuthorizationMetaStoreFilterHook.class.getName().equals(metastoreHook)) {
      LOG.warn(ConfVars.METASTORE_FILTER_HOOK.varname +
          " will be ignored, since hive.security.authorization.manager" +
          " is set to instance of HiveAuthorizerFactory.");
    }
    sessionConf.setVar(ConfVars.METASTORE_FILTER_HOOK,
        AuthorizationMetaStoreFilterHook.class.getName());

    authorizerV2.applyAuthorizationConfigPolicy(sessionConf);
    // update config in Hive thread local as well and init the metastore client
    try {
      Hive.getWithoutRegisterFns(sessionConf).getMSC();
    } catch (Exception e) {
      // catch-all due to some exec time dependencies on session state
      // that would cause ClassNoFoundException otherwise
      throw new HiveException(e.getMessage(), e);
    }

    // set a marker that this conf has been processed.
    sessionConf.set(CONFIG_AUTHZ_SETTINGS_APPLIED_MARKER, Boolean.TRUE.toString());
  }

  public Object getActiveAuthorizer() {
    return getAuthorizationMode() == AuthorizationMode.V1 ?
        getAuthorizer() : getAuthorizerV2();
  }

  public Class<?> getAuthorizerInterface() {
    return getAuthorizationMode() == AuthorizationMode.V1 ?
        HiveAuthorizationProvider.class : HiveAuthorizer.class;
  }

  public void setActiveAuthorizer(Object authorizer) {
    if (authorizer instanceof HiveAuthorizationProvider) {
      this.authorizer = (HiveAuthorizationProvider)authorizer;
    } else if (authorizer instanceof HiveAuthorizer) {
      this.authorizerV2 = (HiveAuthorizer) authorizer;
    } else if (authorizer != null) {
      throw new IllegalArgumentException("Invalid authorizer " + authorizer);
    }
  }

  /**
   * @param conf
   * @return per-session temp file
   * @throws IOException
   */
  private static File createTempFile(HiveConf conf) throws IOException {
    String lScratchDir = HiveConf.getVar(conf, HiveConf.ConfVars.LOCAL_SCRATCH_DIR);
    String sessionID = conf.getVar(HiveConf.ConfVars.HIVE_SESSION_ID);

    return FileUtils.createTempFile(lScratchDir, sessionID, ".pipeout");
  }

  /**
   * get the current session.
   */
  public static SessionState get() {
    return tss.get().state;
  }

  public static HiveConf getSessionConf() {
    SessionStates state = tss.get();
    if (state.conf == null) {
      state.attach(new HiveConf());
    }
    return state.conf;
  }

  public static Registry getRegistry() {
    SessionState session = get();
    return session != null ? session.registry : null;
  }

  public static Registry getRegistryForWrite() {
    Registry registry = getRegistry();
    if (registry == null) {
      throw new RuntimeException("Function registry for session is not initialized");
    }
    return registry;
  }

  /**
   * get hiveHistory object which does structured logging.
   *
   * @return The hive history object
   */
  public HiveHistory getHiveHistory() {
    return hiveHist;
  }

  /**
   * Update the history if set hive.session.history.enabled
   *
   * @param historyEnabled
   * @param ss
   */
  public void updateHistory(boolean historyEnabled, SessionState ss) {
    if (historyEnabled) {
      // Uses a no-op proxy
      if (ss.hiveHist.getHistFileName() == null) {
        ss.hiveHist = new HiveHistoryImpl(ss);
      }
    } else {
      if (ss.hiveHist.getHistFileName() != null) {
        ss.hiveHist = HiveHistoryProxyHandler.getNoOpHiveHistoryProxy();
      }
    }
  }

  /**
   * Create a session ID. Looks like:
   *   $user_$pid@$host_$date
   * @return the unique string
   */
  private static String makeSessionId() {
    return UUID.randomUUID().toString();
  }

  public String getLastCommand() {
    return lastCommand;
  }

  public void setLastCommand(String lastCommand) {
    this.lastCommand = lastCommand;
  }

  /**
   * This class provides helper routines to emit informational and error
   * messages to the user and log4j files while obeying the current session's
   * verbosity levels.
   *
   * NEVER write directly to the SessionStates standard output other than to
   * emit result data DO use printInfo and printError provided by LogHelper to
   * emit non result data strings.
   *
   * It is perfectly acceptable to have global static LogHelper objects (for
   * example - once per module) LogHelper always emits info/error to current
   * session as required.
   */
  public static class LogHelper {

    protected Logger LOG;
    protected boolean isSilent;

    private final StringBuilder querySummary = new StringBuilder();
    private boolean collectQuerySummary;

    public LogHelper(Logger LOG) {
      this(LOG, false);
    }

    public LogHelper(Logger LOG, boolean isSilent) {
      this.LOG = LOG;
      this.isSilent = isSilent;
    }

    /**
     * Get the console output stream for HiveServer2 or HiveCli.
     * @return The output stream
     */
    public PrintStream getOutStream() {
      SessionState ss = SessionState.get();
      return ((ss != null) && (ss.out != null)) ? ss.out : System.out;
    }

    /**
     * Get the console info stream for HiveServer2 or HiveCli.
     * @return The info stream
     */
    public static PrintStream getInfoStream() {
      SessionState ss = SessionState.get();
      return ((ss != null) && (ss.info != null)) ? ss.info : getErrStream();
    }

    /**
     * Get the console error stream for HiveServer2 or HiveCli.
     * @return The error stream
     */
    public static PrintStream getErrStream() {
      SessionState ss = SessionState.get();
      return ((ss != null) && (ss.err != null)) ? ss.err : System.err;
    }

    /**
     * Get the child process output stream for HiveServer2 or HiveCli.
     * @return The child process output stream
     */
    public PrintStream getChildOutStream() {
      SessionState ss = SessionState.get();
      return ((ss != null) && (ss.childOut != null)) ? ss.childOut : System.out;
    }

    /**
     * Get the child process error stream for HiveServer2 or HiveCli.
     * @return The child process error stream
     */
    public PrintStream getChildErrStream() {
      SessionState ss = SessionState.get();
      return ((ss != null) && (ss.childErr != null)) ? ss.childErr : System.err;
    }

    /**
     * Is the logging to the info stream is enabled, or not.
     * @return True if the logging is disabled to the HiveServer2 or HiveCli info stream
     */
    public boolean getIsSilent() {
      SessionState ss = SessionState.get();
      // use the session or the one supplied in constructor
      return (ss != null) ? ss.getIsSilent() : isSilent;
    }


    /**
     * Is the logging to the info stream is enabled, or not.
     * @return True if the logging is disabled to the HiveServer2 or HiveCli info stream
     */
    public boolean getIsQtestLogging() {
      SessionState ss = SessionState.get();
      // use the session or the one supplied in constructor
      return (ss != null) ? ss.getIsQtestLogging() : false;
    }

    /**
     * Logs into the log file.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param info The log message
     */
    public void logInfo(String info) {
      logInfo(info, null);
    }

    /**
     * Logs into the log file. Handles an extra detail which will not be printed if null.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param info The log message
     * @param detail Extra detail to log which will be not printed if null
     */
    public void logInfo(String info, String detail) {
      LOG.info(info + StringUtils.defaultString(detail));
    }

    /**
     * Logs info into the log file, and if the LogHelper is not silent then into the HiveServer2 or
     * HiveCli info stream too.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param info The log message
     */
    public void printInfo(String info) {
      printInfo(info, null);
    }

    /**
     * Logs info into the log file, and if not silent then into the HiveServer2 or HiveCli info
     * stream too. The isSilent parameter is used instead of the LogHelper isSilent attribute.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param info The log message
     * @param isSilent If true then the message will not be printed to the info stream
     */
    public void printInfo(String info, boolean isSilent) {
      printInfo(info, null, isSilent);
    }

    /**
     * Logs info into the log file, and if the LogHelper is not silent then into the HiveServer2 or
     * HiveCli info stream too. Handles an extra detail which will not be printed if null.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param info The log message
     * @param detail Extra detail to log which will be not printed if null
     */
    public void printInfo(String info, String detail) {
      printInfo(info, detail, getIsSilent());
    }

    /**
     * Logs info into the log file, and if not silent then into the HiveServer2 or HiveCli info
     * stream too. Handles an extra detail which will not be printed if null.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param info The log message
     * @param detail Extra detail to log which will be not printed if null
     * @param isSilent If true then the message will not be printed to the info stream
     */
    public void printInfo(String info, String detail, boolean isSilent) {
      if (!isSilent) {
        getInfoStream().println(info);
      }
      if (collectQuerySummary){
        querySummary.append(info);
        querySummary.append("\n");
      }
      LOG.info(info + StringUtils.defaultString(detail));
    }

    /**
     * Logs warn into the log file, and if the LogHelper is not silent then into the HiveServer2 or
     * HiveCli info stream too.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param warn The log message
     */
    public void printWarn(String warn) {
      printWarn(warn, null);
    }

    /**
     * Logs warn into the log file, and if the LogHelper is not silent then into the HiveServer2 or
     * HiveCli info stream too. Handles an extra detail which will not be printed if null.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param warn The log message
     * @param detail Extra detail to log which will be not printed if null
     */
    public void printWarn(String warn, String detail) {
      printWarn(warn, detail, getIsSilent());
    }

    /**
     * Logs warn into the log file, and if not silent then into the HiveServer2 or HiveCli info
     * stream too. Handles an extra detail which will not be printed if null.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param warn The log message
     * @param detail Extra detail to log which will be not printed if null
     * @param isSilent If true then the message will not be printed to the info stream
     */
    public void printWarn(String warn, String detail, boolean isSilent) {
      if (!isSilent) {
        getInfoStream().println(warn);
      }
      LOG.warn(warn + StringUtils.defaultString(detail));
    }

    /**
     * Logs an error into the log file, and into the HiveServer2 or HiveCli error stream too.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param error The log message
     */
    public void printError(String error) {
      printError(error, null);
    }

    /**
     * Logs an error into the log file, and into the HiveServer2 or HiveCli error stream too.
     * Handles an extra detail which will not be printed if null.
     * BeeLine uses the operation log file to show the logs to the user, so depending on the
     * BeeLine settings it could be shown to the user.
     * @param error The log message
     * @param detail Extra detail to log which will be not printed if null
     */
    public void printError(String error, String detail) {
      if(!getIsSilent() || getIsQtestLogging()) {
        getErrStream().println(error);
      }
      if (collectQuerySummary){
        querySummary.append(error);
        querySummary.append("\n");
      }
      LOG.error(error + StringUtils.defaultString(detail));
    }

    public String getQuerySummary() {
      return querySummary.toString();
    }

    public void startSummary() {
      querySummary.setLength(0);
      querySummary.append("\n");
      collectQuerySummary = true;
    }

    public void endSummary() {
      collectQuerySummary = false;
    }
  }

  private static LogHelper _console;

  /**
   * initialize or retrieve console object for SessionState.
   */
  public static LogHelper getConsole() {
    if (_console == null) {
      Logger LOG = LoggerFactory.getLogger("SessionState");
      _console = new LogHelper(LOG);
    }
    return _console;
  }

  /**
   *
   * @return username from current SessionState authenticator. username will be
   *         null if there is no current SessionState object or authenticator is
   *         null.
   */
  public static String getUserFromAuthenticator() {
    if (SessionState.get() != null && SessionState.get().getAuthenticator() != null) {
      return SessionState.get().getAuthenticator().getUserName();
    }
    return null;
  }

  public static List<String> getGroupsFromAuthenticator() {
    if (SessionState.get() != null && SessionState.get().getAuthenticator() != null) {
      return SessionState.get().getAuthenticator().getGroupNames();
    }
    return null;
  }

  static void validateFiles(List<String> newFiles) throws IllegalArgumentException {
    SessionState ss = SessionState.get();
    Configuration conf = (ss == null) ? new Configuration() : ss.getConf();

    for (String newFile : newFiles) {
      try {
        if (Utilities.realFile(newFile, conf) == null) {
          String message = newFile + " does not exist";
          throw new IllegalArgumentException(message);
        }
      } catch (IOException e) {
        String message = "Unable to validate " + newFile;
        throw new IllegalArgumentException(message, e);
      }
    }
  }

  /**
   * Load the jars under the path specified in hive.aux.jars.path property. Add
   * the jars to the classpath so the local task can refer to them.
   * @throws IOException
   */
  public void loadAuxJars() throws IOException {
    String[] jarPaths = StringUtils.split(sessionConf.getAuxJars(), ',');
    if (ArrayUtils.isEmpty(jarPaths)) {
      return;
    }
    AddToClassPathAction addAction = new AddToClassPathAction(
        SessionState.get().getConf().getClassLoader(), Arrays.asList(jarPaths)
        );
    final ClassLoader currentCLoader = AccessController.doPrivileged(addAction);
    sessionConf.setClassLoader(currentCLoader);
    Thread.currentThread().setContextClassLoader(currentCLoader);
  }

  /**
   * Reload the jars under the path specified in hive.reloadable.aux.jars.path property.
   *
   * @throws IOException
   */
  public void loadReloadableAuxJars() throws IOException {
    LOG.info("Reloading auxiliary JAR files");

    final String renewableJarPath = sessionConf.getVar(ConfVars.HIVE_RELOADABLE_JARS);
    // do nothing if this property is not specified or empty
    if (StringUtils.isBlank(renewableJarPath)) {
      LOG.warn("Configuration {} not specified", ConfVars.HIVE_RELOADABLE_JARS);
      return;
    }

    // load jars under the hive.reloadable.aux.jars.path
    final Set<String> jarPaths = FileUtils.getJarFilesByPath(renewableJarPath, sessionConf);

    LOG.info("Auxiliary JAR files discovered for reload: {}", jarPaths);

    // remove the previous renewable jars
    if (!preReloadableAuxJars.isEmpty()) {
      Utilities.removeFromClassPath(preReloadableAuxJars.toArray(new String[0]));
    }

    if (!jarPaths.isEmpty()) {
      AddToClassPathAction addAction = new AddToClassPathAction(
          SessionState.get().getConf().getClassLoader(), jarPaths);
      final ClassLoader currentCLoader = AccessController.doPrivileged(addAction);
      sessionConf.setClassLoader(currentCLoader);
      Thread.currentThread().setContextClassLoader(currentCLoader);
    }

    preReloadableAuxJars.clear();
    preReloadableAuxJars.addAll(jarPaths);
  }

  static void registerJars(List<String> newJars) throws IllegalArgumentException {
    LogHelper console = getConsole();
    try {
      AddToClassPathAction addAction = new AddToClassPathAction(
          Thread.currentThread().getContextClassLoader(), newJars);
      final ClassLoader newLoader = AccessController.doPrivileged(addAction);
      Thread.currentThread().setContextClassLoader(newLoader);
      SessionState.get().getConf().setClassLoader(newLoader);
      console.printInfo("Added " + newJars + " to class path");
    } catch (Exception e) {
      String message = "Unable to register " + newJars;
      throw new IllegalArgumentException(message, e);
    }
  }

  static boolean unregisterJar(List<String> jarsToUnregister) {
    LogHelper console = getConsole();
    try {
      Utilities.removeFromClassPath(jarsToUnregister.toArray(new String[0]));
      console.printInfo("Deleted " + jarsToUnregister + " from class path");
      return true;
    } catch (IOException e) {
      console.printError("Unable to unregister " + jarsToUnregister
          + "\nException: " + e.getMessage(), "\n"
              + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return false;
    }
  }

  public String getATSDomainId() {
    return atsDomainId;
  }

  public void setATSDomainId(String domainId) {
    this.atsDomainId = domainId;
  }

  /**
   * ResourceType.
   *
   */
  public static enum ResourceType {
    FILE,

    JAR {
      @Override
      public void preHook(Set<String> cur, List<String> s) throws IllegalArgumentException {
        super.preHook(cur, s);
        registerJars(s);
      }
      @Override
      public void postHook(Set<String> cur, List<String> s) {
        unregisterJar(s);
      }
    },
    ARCHIVE;

    public void preHook(Set<String> cur, List<String> s) throws IllegalArgumentException {
      validateFiles(s);
    }
    public void postHook(Set<String> cur, List<String> s) {
    }
  };

  public static ResourceType find_resource_type(String s) {

    s = s.trim().toUpperCase();

    try {
      return ResourceType.valueOf(s);
    } catch (IllegalArgumentException e) {
    }

    // try singular
    if (s.endsWith("S")) {
      s = s.substring(0, s.length() - 1);
    } else {
      return null;
    }

    try {
      return ResourceType.valueOf(s);
    } catch (IllegalArgumentException e) {
    }
    return null;
  }

  public String add_resource(ResourceType t, String value)
      throws RuntimeException {
    List<String> added = add_resources(t, Arrays.asList(value));
    if (added == null || added.isEmpty()) {
      return null;
    }
    return added.get(0);
  }

  public List<String> add_resources(ResourceType t, Collection<String> values) throws RuntimeException {
    Set<String> resourceSet = resourceMaps.getResourceSet(t);
    Map<String, Set<String>> resourcePathMap = resourceMaps.getResourcePathMap(t);
    Map<String, Set<String>> reverseResourcePathMap = resourceMaps.getReverseResourcePathMap(t);
    List<String> localized = new ArrayList<String>();
    try {
      for (String value : values) {
        String key;

        //get the local path of downloaded jars.
        List<URI> downloadedURLs = resolveAndDownload(t, value);

        if (ResourceDownloader.isIvyUri(value)) {
          // get the key to store in map
          key = ResourceDownloader.createURI(value).getAuthority();
        } else {
          // for local file and hdfs, key and value are same.
          key = downloadedURLs.get(0).toString();
        }
        Set<String> downloadedValues = new HashSet<String>();

        for (URI uri : downloadedURLs) {
          String resourceValue = uri.getPath();
          downloadedValues.add(resourceValue);
          localized.add(resourceValue);
          if (reverseResourcePathMap.containsKey(resourceValue)) {
            if (!reverseResourcePathMap.get(resourceValue).contains(key)) {
              reverseResourcePathMap.get(resourceValue).add(key);
            }
          } else {
            Set<String> addSet = new HashSet<String>();
            addSet.add(key);
            reverseResourcePathMap.put(resourceValue, addSet);

          }
        }
        resourcePathMap.put(key, downloadedValues);
      }
      t.preHook(resourceSet, localized);

    } catch (RuntimeException e) {
      getConsole().printError(e.getMessage(), "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e));
      throw e;
    } catch (URISyntaxException | IOException e) {
      getConsole().printError(e.getMessage());
      throw new RuntimeException(e);
    }
    getConsole().printInfo("Added resources: " + values);
    resourceSet.addAll(localized);
    return localized;
  }

  @VisibleForTesting
  protected List<URI> resolveAndDownload(ResourceType resourceType, String value)
      throws URISyntaxException, IOException {
    List<URI> uris = resourceDownloader.resolveAndDownload(value);
    if (ResourceDownloader.isHdfsUri(value)) {
      assert uris.size() == 1 : "There should only be one URI localized-resource.";
      resourceMaps.getLocalHdfsLocationMap(resourceType).put(uris.get(0).toString(), value);
    }
    return uris;
  }

  public void delete_resources(ResourceType t, List<String> values) {
    Set<String> resources = resourceMaps.getResourceSet(t);
    if (resources == null || resources.isEmpty()) {
      return;
    }

    Map<String, Set<String>> resourcePathMap = resourceMaps.getResourcePathMap(t);
    Map<String, Set<String>> reverseResourcePathMap = resourceMaps.getReverseResourcePathMap(t);
    List<String> deleteList = new LinkedList<String>();
    for (String value : values) {
      String key = value;
      try {
        if (ResourceDownloader.isIvyUri(value)) {
          key = ResourceDownloader.createURI(value).getAuthority();
        }
        else if (ResourceDownloader.isHdfsUri(value)) {
          String removedKey = removeHdfsFilesFromMapping(t, value);
          // remove local copy of HDFS location from resource map.
          if (removedKey != null) {
            key = removedKey;
          }
        }
      } catch (URISyntaxException e) {
        throw new RuntimeException("Invalid uri string " + value + ", " + e.getMessage());
      }

      // get all the dependencies to delete
      Set<String> resourcePaths = resourcePathMap.get(key);
      if (resourcePaths == null) {
        return;
      }
      for (String resourceValue : resourcePaths) {
        reverseResourcePathMap.get(resourceValue).remove(key);

        // delete a dependency only if no other resource depends on it.
        if (reverseResourcePathMap.get(resourceValue).isEmpty()) {
          deleteList.add(resourceValue);
          reverseResourcePathMap.remove(resourceValue);
        }
      }
      resourcePathMap.remove(key);
    }
    t.postHook(resources, deleteList);
    resources.removeAll(deleteList);
  }

  public Set<String> list_resource(ResourceType t, List<String> filter) {
    Set<String> orig = resourceMaps.getResourceSet(t);
    if (orig == null) {
      return null;
    }
    if (filter == null) {
      return orig;
    } else {
      Set<String> fnl = new HashSet<String>();
      for (String one : orig) {
        if (filter.contains(one)) {
          fnl.add(one);
        }
      }
      return fnl;
    }
  }

  private String removeHdfsFilesFromMapping(ResourceType t, String file){
    String key = null;
    if (resourceMaps.getLocalHdfsLocationMap(t).containsValue(file)){
      for (Map.Entry<String, String> entry : resourceMaps.getLocalHdfsLocationMap(t).entrySet()){
        if (entry.getValue().equals(file)){
          key = entry.getKey();
          resourceMaps.getLocalHdfsLocationMap(t).remove(key);
        }
      }
    }
    return key;
  }

  public Set<String> list_local_resource(ResourceType type) {
    Set<String> resources = new HashSet<String>(list_resource(type, null));
    Set<String> set = resourceMaps.getResourceSet(type);
    for (String file : set){
      if (resourceMaps.getLocalHdfsLocationMap(ResourceType.FILE).containsKey(file)){
        resources.remove(file);
      }
      if (resourceMaps.getLocalHdfsLocationMap(ResourceType.JAR).containsKey(file)){
        resources.remove(file);
      }
    }
    return resources;
  }

  public Set<String> list_hdfs_resource(ResourceType type) {
    Set<String> set = resourceMaps.getResourceSet(type);
    Set<String> result = new HashSet<String>();
    for (String file : set){
      if (resourceMaps.getLocalHdfsLocationMap(ResourceType.FILE).containsKey(file)){
        result.add(resourceMaps.getLocalHdfsLocationMap(ResourceType.FILE).get(file));
      }
      if (resourceMaps.getLocalHdfsLocationMap(ResourceType.JAR).containsKey(file)){
        result.add(resourceMaps.getLocalHdfsLocationMap(ResourceType.JAR).get(file));
      }
    }
    return result;
  }

  public void delete_resources(ResourceType t) {
    Set<String> resources = resourceMaps.getResourceSet(t);
    if (resources != null && !resources.isEmpty()) {
      delete_resources(t, new ArrayList<String>(resources));
      resourceMaps.getResourceMap().remove(t);
      resourceMaps.getAllLocalHdfsLocationMap().remove(t);
    }
  }

  public HiveAuthorizationProvider getAuthorizer() {
    setupAuth();
    return authorizer;
  }

  public void setAuthorizer(HiveAuthorizationProvider authorizer) {
    this.authorizer = authorizer;
  }

  public HiveAuthorizer getAuthorizerV2() {
    setupAuth();
    return authorizerV2;
  }

  public HiveAuthenticationProvider getAuthenticator() {
    setupAuth();
    return authenticator;
  }

  public void setAuthenticator(HiveAuthenticationProvider authenticator) {
    this.authenticator = authenticator;
  }

  public CreateTableAutomaticGrant getCreateTableGrants() {
    setupAuth();
    return createTableGrants;
  }

  public void setCreateTableGrants(CreateTableAutomaticGrant createTableGrants) {
    this.createTableGrants = createTableGrants;
  }

  public Map<String, MapRedStats> getMapRedStats() {
    return mapRedStats;
  }

  public void setMapRedStats(Map<String, MapRedStats> mapRedStats) {
    this.mapRedStats = mapRedStats;
  }

  public void setStackTraces(Map<String, List<List<String>>> stackTraces) {
    this.stackTraces = stackTraces;
  }

  public Map<String, List<List<String>>> getStackTraces() {
    return stackTraces;
  }

  public Map<String, String> getOverriddenConfigurations() {
    if (overriddenConfigurations == null) {
      // Must be deterministic order map for consistent q-test output across Java versions
      overriddenConfigurations = new LinkedHashMap<>();
    }
    return overriddenConfigurations;
  }

  public Map<String, String> getOverriddenMetaConfigurations() {
    if (overriddenMetaConfigurations == null) {
      // Must be deterministic order map for consistent q-test output across Java versions
      overriddenMetaConfigurations = new LinkedHashMap<>();
    }
    return overriddenMetaConfigurations;
  }

  public void setOverriddenConfigurations(Map<String, String> overriddenConfigurations) {
    this.overriddenConfigurations = overriddenConfigurations;
  }

  public Map<String, List<String>> getLocalMapRedErrors() {
    return localMapRedErrors;
  }

  public void addLocalMapRedErrors(String id, List<String> localMapRedErrors) {
    if (!this.localMapRedErrors.containsKey(id)) {
      this.localMapRedErrors.put(id, new ArrayList<String>());
    }

    this.localMapRedErrors.get(id).addAll(localMapRedErrors);
  }

  public void setLocalMapRedErrors(Map<String, List<String>> localMapRedErrors) {
    this.localMapRedErrors = localMapRedErrors;
  }

  public String getCurrentDatabase() {
    if (currentDatabase == null) {
      currentDatabase = DEFAULT_DATABASE_NAME;
    }
    return currentDatabase;
  }

  public void setCurrentDatabase(String currentDatabase) {
    this.currentDatabase = currentDatabase;
  }

  public String getCurrentCatalog() {
    if (currentCatalog == null) {
      currentCatalog = MetaStoreUtils.getDefaultCatalog(getConf());
    }
    return currentCatalog;
  }

  public void setCurrentCatalog(String currentCatalog) {
    this.currentCatalog = currentCatalog;
  }

  public void close() throws IOException {
    // de-register session state
    sessionStates.remove(this);
    for (Closeable cleanupItem : cleanupItems) {
      try {
        cleanupItem.close();
      } catch (Exception err) {
        LOG.error("Error processing SessionState cleanup item " + cleanupItem.toString(), err);
      }
    }

    registry.clear();
    if (txnMgr != null) {
      txnMgr.closeTxnManager();
    }
    JavaUtils.closeClassLoadersTo(sessionConf.getClassLoader(), parentLoader);
    Utilities.restoreSessionSpecifiedClassLoader(getClass().getClassLoader());
    File resourceDir =
        new File(getConf().getVar(HiveConf.ConfVars.DOWNLOADED_RESOURCES_DIR));
    LOG.debug("Removing resource dir " + resourceDir);
    try {
      if (resourceDir.exists()) {
        FileUtils.deleteDirectory(resourceDir);
      }
    } catch (IOException e) {
      LOG.info("Error removing session resource dir " + resourceDir, e);
    } finally {
      detachSession();
    }

    try {
      if (tezSessionState != null) {
        TezSessionPoolManager.closeIfNotDefault(tezSessionState, false);
      }
    } catch (Exception e) {
      LOG.info("Error closing tez session", e);
    } finally {
      setTezSession(null);
    }

    try {
      registry.closeCUDFLoaders();
      dropSessionPaths(sessionConf);
      unCacheDataNucleusClassLoaders();
    } finally {
      // removes the threadlocal variables, closes underlying HMS connection
      if (hiveDb != null) {
        hiveDb.close(true);
        hiveDb = null;
      }
    }
    progressMonitor = null;
    // Hadoop's ReflectionUtils caches constructors for the classes it instantiated.
    // In UDFs, this can result in classloaders not getting GCed for a temporary function,
    // resulting in a PermGen leak when used extensively from HiveServer2
    // There are lots of places where hadoop's ReflectionUtils is still used. Until all of them are
    // cleared up, we would have to retain this to avoid mem leak.
    clearReflectionUtilsCache();
    for (Object each : dynamicVars.values()) {
      if (each instanceof Closeable) {
        ((Closeable)each).close();
      }
    }
    dynamicVars.clear();
  }

  private static void cleanUpAllSessionStates() {
    ExecutorService cleanupExecutor = Executors.newCachedThreadPool();
    try {
      CompletableFuture<Void> allCleanupTasks = CompletableFuture.allOf(sessionStates.stream()
              .map(sessionState -> CompletableFuture.runAsync(() -> {
                  try {
                      LOG.info("Closing session state: {}", sessionState.getSessionId());
                      sessionState.close();
                  } catch (IOException e) {
                      throw new CompletionException(e);
                  }
              }, cleanupExecutor).exceptionally(e -> {
                  LOG.error("Problem closing session state", e);
                  return null;
              })).toArray(CompletableFuture[]::new));
      try {
        allCleanupTasks.get(60, TimeUnit.SECONDS);
      } catch (Exception e) {
        LOG.error("Failed to close all session states", e);
      }
    } finally {
      // shutdown cleanup executor after all tasks finished/timeout
      cleanupExecutor.shutdownNow();
    }
  }

  private void clearReflectionUtilsCache() {
    Method clearCacheMethod;
    try {
      clearCacheMethod = ReflectionUtils.class.getDeclaredMethod("clearCache");
      if (clearCacheMethod != null) {
        clearCacheMethod.setAccessible(true);
        clearCacheMethod.invoke(null);
        LOG.debug("Cleared Hadoop ReflectionUtils CONSTRUCTOR_CACHE");
      }
    } catch (Exception e) {
      LOG.info("Failed to clear up Hadoop ReflectionUtils CONSTRUCTOR_CACHE", e);
    }
  }

  private void unCacheDataNucleusClassLoaders() {
    try {
      boolean isLocalMetastore = HiveConfUtil.isEmbeddedMetaStore(
          MetastoreConf.getVar(sessionConf, MetastoreConf.ConfVars.THRIFT_URIS));
      if (isLocalMetastore) {

        String rawStoreImpl =
            MetastoreConf.getVar(sessionConf, MetastoreConf.ConfVars.RAW_STORE_IMPL);
        String realStoreImpl;
        if (rawStoreImpl.equals(CachedStore.class.getName())) {
          realStoreImpl =
              MetastoreConf.getVar(sessionConf, MetastoreConf.ConfVars.CACHED_RAW_STORE_IMPL);
        } else {
          realStoreImpl = rawStoreImpl;
        }
        Class<?> clazz = Class.forName(realStoreImpl);
        if (ObjectStore.class.isAssignableFrom(clazz)) {
          PersistenceManagerProvider.clearOutPmfClassLoaderCache();
        }
      }
    } catch (Exception e) {
      LOG.info("Failed to remove classloaders from DataNucleus ", e);
    }
  }

  public AuthorizationMode getAuthorizationMode(){
    setupAuth();
    if(authorizer != null){
      return AuthorizationMode.V1;
    }else if(authorizerV2 != null){
      return AuthorizationMode.V2;
    }
    //should not happen - this should not get called before this.start() is called
    throw new AssertionError("Authorization plugins not initialized!");
  }

  public boolean isAuthorizationModeV2(){
    return getAuthorizationMode() == AuthorizationMode.V2;
  }

  /**
   * @return  Tries to return an instance of the class whose name is configured in
   *          hive.exec.perf.logger, but if it can't it just returns an instance of
   *          the base PerfLogger class
   *
   */
  public static PerfLogger getPerfLogger() {
    return getPerfLogger(false);
  }

  /**
   * @param resetPerfLogger
   * @return  Tries to return an instance of the class whose name is configured in
   *          hive.exec.perf.logger, but if it can't it just returns an instance of
   *          the base PerfLogger class
   *
   */
  public static PerfLogger getPerfLogger(boolean resetPerfLogger) {
    SessionState ss = get();
    if (ss == null) {
      return PerfLogger.getPerfLogger(null, resetPerfLogger);
    } else {
      return PerfLogger.getPerfLogger(ss.getConf(), resetPerfLogger);
    }
  }

  public TezSessionState getTezSession() {
    return tezSessionState;
  }

  /** Called from TezTask to attach a TezSession to use to the threadlocal. Ugly pattern... */
  public void setTezSession(TezSessionState session) {
    if (tezSessionState == session) {
      return; // The same object.
    }
    if (tezSessionState != null) {
      tezSessionState.markFree();
      tezSessionState.setKillQuery(null);
      tezSessionState = null;
    }
    tezSessionState = session;
    if (session != null) {
      session.markInUse();
      tezSessionState.setKillQuery(getKillQuery());
    }
  }

  @Override
  public String getUserName() {
    return userName;
  }

  /**
   * If authorization mode is v2, then pass it through authorizer so that it can apply
   * any security configuration changes.
   */
  public void applyAuthorizationPolicy() throws HiveException {
    setupAuth();
  }

  public Map<String, Map<String, Table>> getTempTables() {
    return tempTables;
  }

  public Map<String, SemanticAnalyzer> getPreparePlans() {
    return preparePlanMap;
  }

  public Map<String, TempTable> getTempPartitions() {
    return tempPartitions;
  }

  public Map<String, Map<String, ColumnStatisticsObj>> getTempTableColStats() {
    return tempTableColStats;
  }

  /**
   * @return ip address for user running the query
   */
  public String getUserIpAddress() {
    return userIpAddress;
  }

  /**
   * set the ip address for user running the query
   * @param userIpAddress
   */
  public void setUserIpAddress(String userIpAddress) {
    this.userIpAddress = userIpAddress;
  }

  public void addDynamicVar(Object object) {
    dynamicVars.put(object.getClass(), object);
  }

  public <T> T getDynamicVar(Class<T> clazz) {
    Object value = dynamicVars.get(clazz);
    return value == null ? null : clazz.cast(value);
  }

  /**
   * Get the next suffix to use in naming a temporary table created by insert...values
   * @return suffix
   */
  public String getNextValuesTempTableSuffix() {
    return Integer.toString(nextValueTempTableSuffix++);
  }

  /**
   * Initialize current timestamp, other necessary query initialization.
   */
  public void setupQueryCurrentTimestamp() {
    queryCurrentTimestamp = Instant.now();

    // Provide a facility to set current timestamp during tests
    if (sessionConf.getBoolVar(ConfVars.HIVE_IN_TEST)) {
      String overrideTimestampString =
          HiveConf.getVar(sessionConf, HiveConf.ConfVars.HIVE_TEST_CURRENT_TIMESTAMP, (String)null);
      if (overrideTimestampString != null && overrideTimestampString.length() > 0) {
        TimestampTZ zonedDateTime = TimestampTZUtil.convert(
            Timestamp.valueOf(overrideTimestampString), sessionConf.getLocalTimeZone());
        queryCurrentTimestamp = zonedDateTime.getZonedDateTime().toInstant();
      }
    }
  }

  /**
   * Get query current timestamp
   * @return
   */
  public Instant getQueryCurrentTimestamp() {
    return queryCurrentTimestamp;
  }

  public ResourceDownloader getResourceDownloader() {
    return resourceDownloader;
  }

  public void setForwardedAddresses(List<String> forwardedAddresses) {
    this.forwardedAddresses = forwardedAddresses;
  }

  public List<String> getForwardedAddresses() {
    return forwardedAddresses;
  }

  /**
   * Gets the comma-separated reloadable aux jars
   * @return the list of reloadable aux jars
   */
  public String getReloadableAuxJars() {
    return StringUtils.join(preReloadableAuxJars, ',');
  }

  public void updateProgressedPercentage(final double percentage) {
    this.progressMonitor = new ProgressMonitor() {
      @Override
      public List<String> headers() {
        return null;
      }

      @Override
      public List<List<String>> rows() {
        return null;
      }

      @Override
      public String footerSummary() {
        return null;
      }

      @Override
      public long startTime() {
        return 0;
      }

      @Override
      public String executionStatus() {
        return null;
      }

      @Override
      public double progressedPercentage() {
        return percentage;
      }
    };
  }

  public void updateProgressMonitor(ProgressMonitor progressMonitor) {
    this.progressMonitor = progressMonitor;
  }

  public ProgressMonitor getProgressMonitor() {
    return progressMonitor;
  }

  public void setHiveServer2Host(String hiveServer2HostName) {
    this.hiveServer2HostName = hiveServer2HostName;
  }

  public String getHiveServer2Host() {
    return hiveServer2HostName;
  }

  public void setKillQuery(KillQuery killQuery) {
    this.killQuery = killQuery;
  }

  public KillQuery getKillQuery() {
    return killQuery;
  }

  public void addCleanupItem(Closeable item) {
    cleanupItems.add(item);
  }

  public Map<String, FunctionInfo> getCurrentFunctionsInUse() {
    return currentFunctionsInUse;
  }

  /**
   * Retrieves the query cache for the given query.
   * @param queryId the unique identifier of the query
   * @return the cache for the query
   */
  public Map<Object, Object> getQueryCache(String queryId) {
    QueryState qs = getQueryState(queryId);
    if (qs == null) {
      return null;
    }
    return qs.getHMSCache();
  }

  public Hive getHiveDb() throws HiveException {
    if (hiveDb == null) {
      hiveDb = Hive.createHiveForSession(sessionConf);
      // Need to setAllowClose to false. For legacy reasons, the Hive object is stored
      // in thread local storage. If allowClose is true, the session can get closed when
      // the thread goes away which is not desirable when the Hive object is used across
      // different queries in the session.
      hiveDb.setAllowClose(false);
    }
    return hiveDb;
  }
}

class ResourceMaps {

  private final Map<SessionState.ResourceType, Set<String>> resource_map;
  //Given jar to add is stored as key  and all its transitive dependencies as value. Used for deleting transitive dependencies.
  private final Map<SessionState.ResourceType, Map<String, Set<String>>> resource_path_map;
  // stores all the downloaded resources as key and the jars which depend on these resources as values in form of a list. Used for deleting transitive dependencies.
  private final Map<SessionState.ResourceType, Map<String, Set<String>>> reverse_resource_path_map;
  // stores mappings from local to hdfs location for all resource types.
  private final Map<SessionState.ResourceType, Map<String, String>> local_hdfs_resource_map;

  public ResourceMaps() {
    resource_map = new HashMap<SessionState.ResourceType, Set<String>>();
    resource_path_map = new HashMap<SessionState.ResourceType, Map<String, Set<String>>>();
    reverse_resource_path_map = new HashMap<SessionState.ResourceType, Map<String, Set<String>>>();
    local_hdfs_resource_map = new HashMap<SessionState.ResourceType, Map<String, String>>();
  }

  public Map<SessionState.ResourceType, Set<String>> getResourceMap() {
    return resource_map;
  }

  public Map<SessionState.ResourceType, Map<String, String>> getAllLocalHdfsLocationMap() {
    return local_hdfs_resource_map;
  }

  public Set<String> getResourceSet(SessionState.ResourceType t) {
    Set<String> result = resource_map.get(t);
    if (result == null) {
      result = new HashSet<String>();
      resource_map.put(t, result);
    }
    return result;
  }

  public Map<String, Set<String>> getResourcePathMap(SessionState.ResourceType t) {
    Map<String, Set<String>> result = resource_path_map.get(t);
    if (result == null) {
      result = new HashMap<String, Set<String>>();
      resource_path_map.put(t, result);
    }
    return result;
  }

  public Map<String, Set<String>> getReverseResourcePathMap(SessionState.ResourceType t) {
    Map<String, Set<String>> result = reverse_resource_path_map.get(t);
    if (result == null) {
      result = new HashMap<String, Set<String>>();
      reverse_resource_path_map.put(t, result);
    }
    return result;
  }

  public Map<String, String> getLocalHdfsLocationMap(SessionState.ResourceType type){
    Map<String, String> result = local_hdfs_resource_map.get(type);
    if (result == null) {
      result = new HashMap<String, String>();
      local_hdfs_resource_map.put(type, result);
    }
    return result;
  }
}
