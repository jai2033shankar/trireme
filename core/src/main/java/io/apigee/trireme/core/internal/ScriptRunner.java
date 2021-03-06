/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.core.internal;

import io.apigee.trireme.core.ArgUtils;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.modules.AbstractFilesystem;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.NativeModule;
import io.apigee.trireme.kernel.PathTranslator;
import io.apigee.trireme.kernel.fs.AdvancedFilesystem;
import io.apigee.trireme.kernel.fs.BasicFilesystem;
import io.apigee.trireme.kernel.net.NetworkPolicy;
import io.apigee.trireme.kernel.net.SelectorHandler;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class actually runs the script.
 */
public class ScriptRunner
    implements NodeRuntime, Callable<ScriptStatus>
{
    public static final String RUNNER = "runner";

    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);

    private static final long DEFAULT_DELAY = Integer.MAX_VALUE;

    public static final String TIMEOUT_TIMESTAMP_KEY = "_tickTimeout";

    private final  NodeEnvironment env;
    private        long            now;
    private        AbstractModuleRegistry registry;
    private        File            scriptFile;
    private        String          script;
    private final  NodeScript      scriptObject;
    private final  String[]        args;
    private final  HashMap<String, NativeModule.ModuleImpl> moduleCache = new HashMap<String, NativeModule.ModuleImpl>();
    private final  HashMap<String, Object> internalModuleCache = new HashMap<String, Object>();
    private        ScriptFuture    future;
    private final  CountDownLatch          initialized = new CountDownLatch(1);
    private final  Sandbox                 sandbox;
    private final PathTranslator pathTranslator;
    private final  ExecutorService         asyncPool;
    private final IdentityHashMap<Closeable, Closeable> openHandles =
        new IdentityHashMap<Closeable, Closeable>();

    private final  ConcurrentLinkedQueue<Activity> tickFunctions = new ConcurrentLinkedQueue<Activity>();
    private final  PriorityQueue<Activity>       timerQueue    = new PriorityQueue<Activity>();
    private final  Selector                      selector;
    private        int                           timerSequence;
    private final  AtomicInteger                 pinCount      = new AtomicInteger(0);
    private        BasicFilesystem               filesystem;

    // Globals that are set up for the process
    private NativeModule.NativeImpl nativeModule;
    protected AbstractProcess    process;
    private String              workingDirectory;
    private String              scriptFileName;
    private TriremeProcess      parentProcess;
    private boolean             forceRepl;

    private ScriptableObject    scope;

    public ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox,
                        File scriptFile, String[] args)
    {
        this(so, env, sandbox, args);
        this.scriptFile = scriptFile;

        try {
            this.scriptFileName = pathTranslator.reverseTranslate(scriptFile.getPath());
        } catch (IOException ioe) {
            throw new AssertionError("Error translating file path: " + ioe);
        }
    }

    public ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox,
                        String scriptName, String script,
                        String[] args)
    {
        this(so, env, sandbox, args);
        this.script = script;
        this.scriptFileName = scriptName;
    }

    public ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox,
                        String[] args, boolean forceRepl)
    {
        this(so, env, sandbox, args);
        this.forceRepl = forceRepl;
    }

    private ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox,
                         String[] args)
    {
        this.env = env;
        this.scriptObject = so;

        this.args = args;
        this.sandbox = sandbox;
        this.pathTranslator = new PathTranslator();

        if ((sandbox != null) && (sandbox.getFilesystemRoot() != null)) {
            try {
                pathTranslator.setRoot(sandbox.getFilesystemRoot());
            } catch (IOException ioe) {
                throw new AssertionError("Unexpected I/O error setting filesystem root: " + ioe);
            }
        }

        if ((sandbox != null) && (sandbox.getWorkingDirectory() != null)) {
            this.workingDirectory = sandbox.getWorkingDirectory();
        } else if ((sandbox != null) && (sandbox.getFilesystemRoot() != null)) {
            this.workingDirectory = "/";
        } else {
            this.workingDirectory = new File(".").getAbsolutePath();
        }
        pathTranslator.setWorkingDir(workingDirectory);

        if ((sandbox != null) && (sandbox.getAsyncThreadPool() != null)) {
            this.asyncPool = sandbox.getAsyncThreadPool();
        } else {
            this.asyncPool = env.getAsyncPool();
        }

        if ((sandbox != null) && (sandbox.getMounts() != null)) {
            for (Map.Entry<String, String> mount : sandbox.getMounts()) {
                pathTranslator.mount(mount.getKey(), new File(mount.getValue()));
            }
        }

        try {
            this.selector = Selector.open();
        } catch (IOException ioe) {
            throw new AssertionError(ioe);
        }
    }

    public void close()
    {
        try {
            selector.close();
        } catch (IOException ioe) {
            log.debug("Error closing selector", ioe);
        }
    }

    public void setFuture(ScriptFuture future) {
        this.future = future;
    }

    public ScriptFuture getFuture() {
        return future;
    }

    public NodeEnvironment getEnvironment() {
        return env;
    }

    public long getLoopTimestamp() {
        return now;
    }

    public AbstractModuleRegistry getRegistry() {
        return registry;
    }

    public void setRegistry(AbstractModuleRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Sandbox getSandbox() {
        return sandbox;
    }

    @Override
    public NetworkPolicy getNetworkPolicy() {
        return (sandbox == null ? null : sandbox.getNetworkPolicy());
    }

    @Override
    public NodeScript getScriptObject() {
        return scriptObject;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String wd)
        throws IOException
    {
        File wdf = new File(wd);
        if (wdf.isAbsolute()) {
            this.workingDirectory = wd;
        } else {
            File newWdf = new File(this.workingDirectory, wd);
            this.workingDirectory = newWdf.getCanonicalPath();
        }
        pathTranslator.setWorkingDir(this.workingDirectory);
    }

    public Scriptable getScriptScope() {
        return scope;
    }

    public NativeModule.NativeImpl getNativeModule() {
        return nativeModule;
    }

    @Override
    public Selector getSelector() {
        return selector;
    }

    /**
     * This thread pool is a thread pool that has a fixed maximum size and a queue. It is intended to be used
     * for tasks that would block the main thread, but that have a reasonable chance of completing in a
     * timely fashion. For instance, filesystem operations, DNS requests, and certain TLS operations should
     * operate in this pool. The thread pool is also configured with a "caller runs" error policy, which means
     * that if the thread pool is full, operations will run in the main script thread. This will have the effect
     * of slowing down the whole system so that the thread pool will start to catch up, and is preferable to
     * the alternative of just throwing an unrecoverable error. But that means that any tasks that might run
     * for an unreasonably long time, or never exit (like polling file) MUST NOT use this thread pool.
     * In other words, this thread pool is for maintaining system performance.
     */
    @Override
    public ExecutorService getAsyncPool() {
        return asyncPool;
    }

    /**
     * This thread pool is a "cached thread pool" with no maximum size, which means that any task submitted will
     * run in its own thread until it completes. It is less efficient than the "async thread pool" because it
     * does not queue anything. It should not be used for performance. However, any code that needs to allocate
     * a long-running task, such as a file poller, must use this thread pool. We also use this thread pool
     * to allocate a "main" thread for each script.
     */
    @Override
    public ExecutorService getUnboundedPool() {
        return env.getScriptPool();
    }

    public BasicFilesystem getFilesystem() {
        return filesystem;
    }

    public InputStream getStdin() {
        return ((sandbox != null) && (sandbox.getStdin() != null)) ? sandbox.getStdin() : System.in;
    }

    public OutputStream getStdout() {
        return ((sandbox != null) && (sandbox.getStdout() != null)) ? sandbox.getStdout() : System.out;
    }

    public OutputStream getStderr() {
        return ((sandbox != null) && (sandbox.getStderr() != null)) ? sandbox.getStderr() : System.err;
    }

    public TriremeProcess getParentProcess() {
        return parentProcess;
    }

    public AbstractProcess getProcess() {
        return process;
    }

    public void setParentProcess(TriremeProcess parentProcess)
    {
        this.parentProcess = parentProcess;
    }

    /**
     * We use this when spawning child scripts to avoid sending them messages before they are ready.
     */
    public void awaitInitialization()
    {
        try {
            initialized.await();
        } catch (InterruptedException ignore) {
        }
    }

    /**
     * Translate a path based on the root.
     */
    @Override
    public File translatePath(String path)
    {
        // NIO does not like \\?\ UNC path prefix
        if(path.startsWith("\\\\?\\"))
            path = path.substring(4);

        File pf = new File(path);
        /*
        if (!pf.isAbsolute()) {
            pf = new File(pf, workingDirectory);
        }
        */
        return pathTranslator.translate(pf.getPath());
    }

    @Override
    public String reverseTranslatePath(String path)
        throws IOException
    {
        return pathTranslator.reverseTranslate(path);
    }

    public PathTranslator getPathTranslator()
    {
        return pathTranslator;
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    @Override
    public void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj, Object[] args)
    {
       enqueueCallback(f, scope, thisObj, null, args);
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    @Override
    public void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj, Object domain, Object[] args)
    {
        Callback cb = new Callback(f, scope, thisObj, args);
        cb.setDomain((Scriptable)domain);
        tickFunctions.offer(cb);
        selector.wakeup();
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    @Override
    public void enqueueTask(ScriptTask task)
    {
        enqueueTask(task, null);
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    @Override
    public void enqueueTask(ScriptTask task, Object domain)
    {
        Task t = new Task(task, scope);
        t.setDomain((Scriptable)domain);
        tickFunctions.offer(t);
        selector.wakeup();
    }

    @Override
    public void executeScriptTask(Runnable r, Object domain)
    {
        RunnableTask t = new RunnableTask(r);
        t.setDomain((Scriptable)domain);
        tickFunctions.offer(t);
        selector.wakeup();
    }

    /**
     * Immediately execute the specified callback. This function wraps up the function to be called,
     * because certain types of anonymous functions in Rhino can't be called by just calling "call".
     */
    public void executeCallback(Context cx, Object[] args, Function function,
                                Scriptable thisObj)
    {
        process.submitTick(cx, args, function, thisObj, process.getDomain());
    }

    /**
     * This method is used by the "child_process" module when sending an IPC message between child processes
     * in the same JVM.
     *
     * @param message A JavaScript object, String, or Buffer. We will make a copy to prevent confusion.
     * @param child If null, deliver the message to the "process" object. Otherwise, deliver it to the
     *              specified child.
     */
    public void enqueueIpc(Context cx, Object message, final TriremeProcess child)
    {
        Object toDeliver;
        String event = "message";

        if (message == TriremeProcess.IPC_DISCONNECT) {
            event = "disconnect";
            toDeliver = Undefined.instance;

        } else if (message instanceof Buffer.BufferImpl) {
            // Copy the bytes, because a buffer might be modified between apps
            ByteBuffer bb = ((Buffer.BufferImpl)message).getBuffer();
            toDeliver = Buffer.BufferImpl.newBuffer(cx, scope, bb, true);

        } else if (message instanceof Scriptable) {
            // Copy the object because we can't rely on safely sharing them between apps.
            Scriptable s = (Scriptable)message;
            toDeliver = copy(cx, s);
            if (s.has("cmd", s)) {
                String cmd = Context.toString(s.get("cmd", s));
                if (cmd.startsWith("NODE_")) {
                    event = "internalMessage";
                }
            }

        } else if (message instanceof String) {
            // Strings are immutable in Java!
            toDeliver = message;
        } else {
            throw new AssertionError("Unsupported object type for IPC");
        }

        final Object reallyDeliver = toDeliver;
        final String fevent = event;
        if (child == null) {
            // We are called on child's script runtime, so enqueue a task here
            enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    process.emitEvent(fevent, reallyDeliver, cx, scope);
                }
            });

        } else {
            // We are the child's script runtime. Enqueue task that sends to the parent
            // "child" here actually refers to the "child_process" object inside the parent!
            assert(child.getRuntime() != this);
            child.getRuntime().enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    // Now we should be running inside the script thread of the other script
                    child.getOnMessage().call(cx, scope, null, new Object[] { fevent, reallyDeliver });
                }
            });
        }
    }

    /**
     * Copy one JavaScript object to another, taking nested objects into account. Don't copy primitive fields
     * because we assume that they are immutable (string, boolean, and number).
     */
    private Scriptable copy(Context cx, Scriptable s)
    {
        if (s instanceof Function) {
            return null;
        }
        Scriptable t = cx.newObject(scope);
        for (Object id : s.getIds()) {
            if (id instanceof String) {
                String n = (String)id;
                Object val = s.get(n, s);
                if (val instanceof Scriptable) {
                    val = copy(cx, (Scriptable)val);
                }
                t.put(n, t, val);
            } else if (id instanceof Number) {
                int i = ((Number)id).intValue();
                Object val = s.get(i, s);
                if (val instanceof Scriptable) {
                    val = copy(cx, (Scriptable)val);
                }
                t.put(i, t, val);
            } else {
                throw new AssertionError();
            }
        }
        return t;
    }

    @Override
    public Object getDomain()
    {
        return ArgUtils.ensureValid(process.getDomain());
    }

    /**
     * This method puts the task directly on the timer queue, which is unsynchronized. If it is ever used
     * outside the context of the "TimerWrap" module then we need to check for synchronization, add an
     * assertion check, or synchronize the timer queue.
     */
    public Activity createTimer(long delay, boolean repeating, long repeatInterval, ScriptTask task,
                                Scriptable scope)
    {
        Task t = new Task(task, scope);
        long timeout = System.currentTimeMillis() + delay;
        int seq = timerSequence++;

        if (log.isDebugEnabled()) {
            log.debug("Going to fire timeout {} at {}", seq, timeout);
        }
        t.setId(seq);
        t.setTimeout(timeout);
        if (repeating) {
            t.setInterval(repeatInterval);
            t.setRepeating(true);
        }
        timerQueue.add(t);
        selector.wakeup();
        return t;
    }

    /**
     * This is a more generic way of creating a timer that can be used in the kernel, and which
     * works even if we are not in the main thread.
     */
    public Future<Boolean> createTimedTask(Runnable r, long delay, TimeUnit unit, boolean repeating, Object domain)
    {
        final RunnableTask t = new RunnableTask(r);
        t.setDomain((Scriptable) domain);
        t.setTimeout(System.currentTimeMillis() + unit.toMillis(delay));
        t.setRepeating(repeating);
        if (repeating) {
            t.setInterval(delay);
        }

        enqueueTask(new ScriptTask()
        {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                if (!t.isCancelled()) {
                    t.setId(timerSequence++);
                    timerQueue.add(t);
                    selector.wakeup();
                }
            }
        });
        return t;
    }

    @Override
    public void pin()
    {
        int currentPinCount = pinCount.incrementAndGet();
        log.debug("Pin count is now {}", currentPinCount);
    }

    @Override
    public void unPin()
    {
        int currentPinCount = pinCount.decrementAndGet();
        log.debug("Pin count is now {}", currentPinCount);

        if (currentPinCount < 0) {
            log.warn("Negative pin count: {}", currentPinCount);
        }
        if (currentPinCount == 0) {
            selector.wakeup();
        }
    }

    public void setErrno(String err)
    {
        scope.put("errno", scope, err);
    }

    public void clearErrno()
    {
        scope.put("errno", scope, 0);
    }

    public Object getErrno()
    {
        if (scope.has("errno", scope)) {
            Object errno = scope.get("errno", scope);
            if (errno == null) {
                return Context.getUndefinedValue();
            }
            return scope.get("errno", scope);
        }
        return Context.getUndefinedValue();
    }

    @Override
    public void registerCloseable(Closeable c)
    {
        openHandles.put(c, c);
    }

    @Override
    public void unregisterCloseable(Closeable c)
    {
        openHandles.remove(c);
    }

    /**
     * Clean up all the leaked handles and file descriptors.
     */
    private void closeCloseables(Context cx)
    {
        AbstractFilesystem fs = (AbstractFilesystem)requireInternal("fs", cx);
        if (fs == null) {
            // We might still be initializing
            return;
        }
        fs.cleanup();

        for (Closeable c: openHandles.values()) {
            if (log.isDebugEnabled()) {
                log.debug("Closing leaked handle {}", c);
            }
            try {
                c.close();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error closing leaked handle: {}", ioe);
                }
            }
        }
    }

    /**
     * Execute the script.
     */
    @Override
    public ScriptStatus call()
        throws NodeException
    {
        Object ret = env.getContextFactory().call(new ContextAction()
        {
            @Override
            public Object run(Context cx)
            {
                return runScript(cx);
            }
        });
        return (ScriptStatus)ret;
    }

    protected ScriptStatus runScript(Context cx)
    {
        ScriptStatus status;

        if (scriptObject.getDisplayName() != null) {
            try {
                Thread.currentThread().setName("Trireme: " + scriptObject.getDisplayName());
            } catch (SecurityException ignore) {
            }
        }

        cx.putThreadLocal(RUNNER, this);
        now = System.currentTimeMillis();

        try {
            // All scripts get their own global scope. This is a lot safer than sharing them in case a script wants
            // to add to the prototype of String or Date or whatever (as they often do)
            // This uses a bit more memory and in theory slows down script startup but in practice it is
            // a drop in the bucket.
            scope = cx.initStandardObjects();

            // Lazy first-time init of the node version.
            registry.loadRoot(cx);

            try {
                initGlobals(cx);
            } catch (NodeException ne) {
                return new ScriptStatus(ne);
            } finally {
                initialized.countDown();
            }

            if ((scriptFile == null) && (script == null)) {
                // Just have trireme.js process "process.argv"
                process.setForceRepl(forceRepl);
                setRawArgv();
            } else if (scriptFile == null) {
                // If the script was passed as a string, pretend that "-e" was used to "eval" it.
                // We also get here if we were called by "executeModule".
                process.setEval(script);
                process.setPrintEval(scriptObject.isPrintEval());
                setScriptFileArgv(scriptFileName);
            } else {
                // Otherwise, assume that the script was the second argument to "argv".
                setScriptFileArgv(scriptFileName);
            }

            // Run "trireme.js," which is our equivalent of "node.js". It returns a function that takes
            // "process". When done, we may have ticks to execute.
            Script mainScript = registry.getMainScript();
            Function main = (Function)mainScript.exec(cx, scope);

            boolean timing = startTiming(cx);
            try {
                main.call(cx, scope, scope, new Object[] { process });
            } catch (RhinoException re) {
                boolean handled = handleScriptException(cx, re);
                if (!handled) {
                    throw re;
                }
            } finally {
                if (timing) {
                    endTiming(cx);
                }
            }

            status = mainLoop(cx);

        } catch (NodeExitException ne) {
            // This exception is thrown by process.exit()
            status = ne.getStatus();
        } catch (IOException ioe) {
            log.debug("I/O exception processing script: {}", ioe);
            status = new ScriptStatus(ioe);
        } catch (Throwable t) {
            log.debug("Unexpected script error: {}", t);
            status = new ScriptStatus(t);
        }

        log.debug("Script exiting with exit code {}", status.getExitCode());

        if (!status.hasCause() && !process.isExiting()) {
            // Fire the exit callback, but only if we aren't exiting due to an unhandled exception, and "exit"
            // wasn't already fired because we called "exit"
            try {
                process.setExiting(true);
                process.emitEvent("exit", status.getExitCode(), cx, process);
            } catch (NodeExitException ee) {
                // Exit called exit -- allow it to replace the exit code
                log.debug("Script replacing exit code with {}", ee.getCode());
                status = ee.getStatus();
            } catch (RhinoException re) {
                // Many of the unit tests fire exceptions inside exit.
                status = new ScriptStatus(re);
            }
        }

        closeCloseables(cx);
        try {
            OutputStream stdout = getStdout();
            if (stdout != System.out) {
                stdout.close();
            }
            OutputStream stderr = getStderr();
            if (stderr != System.err) {
                stderr.close();
            }
        } catch (IOException ignore) {
        }

        return status;
    }

    /**
     * Process args the way that "node" does -- the "args" array must be the args that any Node.js
     * interpreter can accept, minus "node" at the beginning.
     */
    private void setRawArgv()
        throws NodeException
    {
        // TODO: This will not allow us to support args that affect the Rhino environment, such
        // as "--use-strict", "--harmony", and so on.
        // We should consider changing when args are processed so that we can support them.
        ArrayList<String> argv = new ArrayList<String>(args == null ? 2 : args.length + 2);
        ArrayList<String> vmArgs = new ArrayList<String>(0);
        argv.add(AbstractProcess.EXECUTABLE_NAME);

        // All args that start with "--" are considered args to the VM until we run into one that
        // is not -- presumably that would be the file name. I suspect, however, that for "real node"
        // compatibility we'll have to do even more.
        if (args != null) {
            boolean vmArgsDone = false;
            for (String arg : args) {
                if (vmArgsDone) {
                    argv.add(arg);
                } else if (arg.startsWith("--")) {
                    vmArgs.add(arg);
                } else {
                    argv.add(arg);
                    vmArgsDone = true;
                }
            }
        }

        String[] ret = argv.toArray(new String[argv.size()]);
        if (log.isDebugEnabled()) {
            for (int i = 0; i < ret.length; i++) {
                log.debug("argv[{}] = {}", i, ret[i]);
            }
        }
        process.setArgv(ret);
        process.setExecArgv(vmArgs);

        initVmArgs();
    }

    /**
     * Assume that the script name was passed as an argument to one of the "createScript" functions.
     * In this case, we just use everything in "args" as the command-line arguments, although
     * we prepend "node" and the script name to make it compatible with other Node stuff.
     */
    private void setScriptFileArgv(String scriptName)
        throws NodeException
    {
        String[] finalArgs = new String[args == null ? 2 : args.length + 2];
        finalArgs[0] = AbstractProcess.EXECUTABLE_NAME;
        finalArgs[1] = scriptName;

        if (args != null) {
            System.arraycopy(args, 0, finalArgs, 2, args.length);
        }

        if (log.isDebugEnabled()) {
            for (int i = 0; i < finalArgs.length; i++) {
                log.debug("argv[{}] = {}", i, finalArgs[i]);
            }
        }
        process.setArgv(finalArgs);
        List<String> empty = Collections.emptyList();
        process.setExecArgv(empty);

        initVmArgs();
    }

    private ScriptStatus mainLoop(Context cx)
        throws IOException
    {
        // Exit if there's no work do to but only if we're not pinned by a module.
        // We might exit if there are events on the timer queue if they are not also pinned.
        while (!tickFunctions.isEmpty() || (pinCount.get() > 0) ||
                process.isTickTaskPending() || process.isImmediateTaskPending()) {
            try {
                if ((future != null) && future.isCancelled()) {
                    return ScriptStatus.CANCELLED;
                }

                // Call tick functions scheduled by process.nextTick. Node.js docs for
                // process.nextTick say that these things run before anything else in the event loop
                executeNextTicks(cx);

                // Call tick functions scheduled by Java code.
                executeTicks(cx);

                // If necessary, call into the timer module to fire all the tasks set up with "setImmediate."
                // Again, like regular Node, the docs say that these run before all I/O activity and all timers.
                executeImmediateCallbacks(cx);

                // Calculate how long we will wait in the call to select, taking into consideration
                // what is on the timer queue and if there are pending ticks or immediate tasks.
                now = System.currentTimeMillis();
                long pollTimeout;
                if (!tickFunctions.isEmpty() || process.isTickTaskPending() ||
                    process.isImmediateTaskPending() || (pinCount.get() == 0)) {
                    // Immediate work -- need to keep spinning
                    // Also keep spinning if we have no reason to keep the loop open
                    pollTimeout = 0L;
                } else if (timerQueue.isEmpty()) {
                    pollTimeout = DEFAULT_DELAY;
                } else {
                    Activity nextActivity = timerQueue.peek();
                    pollTimeout = (nextActivity.timeout - now);
                }

                // Check for network I/O and also sleep if necessary.
                // Any new timer or tick will wake up the selector immediately
                if (pollTimeout > 0L) {
                    if (log.isDebugEnabled()) {
                        log.debug("mainLoop: sleeping for {} pinCount = {}", pollTimeout, pinCount.get());
                    }
                    selector.select(pollTimeout);
                } else {
                    selector.selectNow();
                }

                // Fire any selected I/O functions
                executeNetworkCallbacks(cx);

                // Check the timer queue for all expired timers
                executeTimerTasks(cx, now);

            } catch (NodeExitException ne) {
                // This exception is thrown by process.exit()
                return ne.getStatus();
            } catch (RhinoException re) {
                // All domain and process-wide error handling happened before we got here, so
                // if we get a RhinoException here, then we know that it is fatal.
                return new ScriptStatus(re);
            }
        }
        return ScriptStatus.OK;
    }

    private Scriptable makeError(Context cx, RhinoException re)
    {
        if ((re instanceof JavaScriptException) &&
            (((JavaScriptException)re).getValue() instanceof Scriptable)) {
            return (Scriptable)((JavaScriptException)re).getValue();
        } else if (re instanceof EcmaError) {
            return Utils.makeErrorObject(cx, scope, ((EcmaError) re).getErrorMessage(), re);
        } else {
            return Utils.makeErrorObject(cx, scope, re.getMessage(), re);
        }
    }

    private boolean handleScriptException(Context cx, RhinoException re)
    {
        if (re instanceof NodeExitException) {
            return false;
        }

        // Stop script timing before we run this, so that we don't end up timing out the script twice!
        endTiming(cx);

        Function handleFatal = process.getHandleFatal();
        if (handleFatal == null) {
            return false;
        }

        if (log.isDebugEnabled()) {
            log.debug("Handling fatal exception {} domain = {}\n{}",
                      re, System.identityHashCode(process.getDomain()), re.getScriptStackTrace());
            log.debug("Fatal Java exception: {}", re);
        }

        Scriptable error = makeError(cx, re);
        boolean handled =
            Context.toBoolean(handleFatal.call(cx, scope, scope, new Object[] { error }));
        if (log.isDebugEnabled()) {
            log.debug("Handled = {}", handled);
        }
        return handled;
    }

    /**
     * Execute ticks as defined by process.nextTick() and anything put on the queue from Java code.
     * Each one is timed separately, and error handling is done in here
     * so that we fire other things in the loop (such as timers) in the event of an error.
     */
    public void executeTicks(Context cx)
        throws RhinoException
    {
        Activity nextCall;
        do {
            nextCall = tickFunctions.poll();
            if (nextCall != null) {
                boolean timing = startTiming(cx);
                try {
                    nextCall.execute(cx);
                } catch (RhinoException re) {
                    boolean handled = handleScriptException(cx, re);
                    if (!handled) {
                        throw re;
                    } else {
                        // We can't keep looping here, because all these errors could cause starvation.
                        // Let timers and network I/O run instead.
                        return;
                    }
                } finally {
                    if (timing) {
                        endTiming(cx);
                    }
                }
            }
        } while (nextCall != null);
    }

    /**
     * Execute everything set up by nextTick()
     */
    private void executeNextTicks(Context cx)
        throws RhinoException
    {
        if (process.isTickTaskPending()) {
            if (log.isTraceEnabled()) {
                log.trace("Executing ticks");
            }
            boolean timed = startTiming(cx);
            try {
                process.processTickTasks(cx);
            } catch (RhinoException re) {
                boolean handled = handleScriptException(cx, re);
                if (!handled) {
                    throw re;
                }
            } finally {
                if (timed) {
                    endTiming(cx);
                }
            }
        }
    }

    /**
     * Execute everything set up by setImmediate().
     */
    private void executeImmediateCallbacks(Context cx)
        throws RhinoException
    {
        if (process.isImmediateTaskPending()) {
            if (log.isTraceEnabled()) {
                log.trace("Executing immediate tasks");
            }
            boolean timed = startTiming(cx);
            try {
                process.processImmediateTasks(cx);
            } catch (RhinoException re) {
                boolean handled = handleScriptException(cx, re);
                if (!handled) {
                    throw re;
                }
            } finally {
                if (timed) {
                    endTiming(cx);
                }
            }
        }
    }

    /**
     * Execute everything that the selector has told is is ready.
     */
    private void executeNetworkCallbacks(Context cx)
        throws RhinoException
    {
        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
        while (keys.hasNext()) {
            SelectionKey selKey = keys.next();
            keys.remove();
            boolean timed = startTiming(cx);
            try {
                ((SelectorHandler)selKey.attachment()).selected(selKey);
            } catch (RhinoException re) {
                boolean handled = handleScriptException(cx, re);
                if (!handled) {
                    throw re;
                }
            } finally {
                if (timed) {
                    endTiming(cx);
                }
            }
        }
    }

    private void executeTimerTasks(Context cx, long now)
        throws RhinoException
    {
        Activity timed = timerQueue.peek();
        while ((timed != null) && (timed.timeout <= now)) {
            timerQueue.poll();
            if (!timed.cancelled) {
                boolean timing = startTiming(cx);
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Executing timer {}", timed.id);
                    }
                    timed.execute(cx);
                } catch (RhinoException re) {
                    boolean handled = handleScriptException(cx, re);
                    if (!handled) {
                        throw re;
                    }
                } finally {
                    if (timing) {
                        endTiming(cx);
                    }
                }
                if (timed.repeating && !timed.cancelled) {
                    timed.timeout = now + timed.interval;
                    if (log.isDebugEnabled()) {
                        log.debug("Re-registering {} to fire at {}", timed.id, timed.timeout);
                    }
                    timerQueue.add(timed);
                }
            }
            timed = timerQueue.peek();
        }
    }

    /**
     * One-time initialization of the built-in modules and objects.
     */
    private void initGlobals(Context cx)
        throws NodeException
    {
        if (JavaVersion.get().hasAsyncFileIO()) {
            // Java 7 and up -- use new filesystem
            filesystem = new AdvancedFilesystem();
        } else {
            filesystem = new BasicFilesystem();
        }

        try {
            // Need to bootstrap the "native module" before we can do anything
            NativeModule.NativeImpl nativeMod =
              (NativeModule.NativeImpl)initializeModule(NativeModule.MODULE_NAME, AbstractModuleRegistry.ModuleType.PUBLIC, cx, scope);
            this.nativeModule = nativeMod;
            NativeModule.ModuleImpl nativeModMod = NativeModule.ModuleImpl.newModule(cx, scope,
                                                                                     NativeModule.MODULE_NAME, NativeModule.MODULE_NAME);
            nativeModMod.setLoaded(true);
            nativeModMod.setExports(nativeMod);
            cacheModule(NativeModule.MODULE_NAME, nativeModMod);

            // Next we need "process" which takes a bit more care
            process = (AbstractProcess)require("process", cx);
            // Check if we are connected to a parent via API
            process.setConnected(parentProcess != null);
            scope.put("process", scope, process);

        } catch (InvocationTargetException e) {
            throw new NodeException(e);
        } catch (IllegalAccessException e) {
            throw new NodeException(e);
        } catch (InstantiationException e) {
            throw new NodeException(e);
        }
    }

    private void initVmArgs()
        throws NodeException
    {
        for (Object arg : process.getExecArgv()) {
            String sa = (String)arg;
            if ("--expose-gc".equals(arg) || "--expose_gc".equals(arg)) {
                Method gc = Utils.findMethod(AbstractProcess.class, "JsGc");
                FunctionObject gcFunc = new FunctionObject("gc", gc, scope);
                scope.put("gc", scope, gcFunc);
            } else if ("--throw-deprecation".equals(arg)) {
                process.put("throwDeprecation", process, true);
            } else if ("--trace-deprecation".equals(arg)) {
                process.put("traceDeprecation", process, true);
            } else if ("--no-deprecation".equals(arg)) {
                process.put("throwDeprecation", process, false);
                process.put("traceDeprecation", process, false);
            } else if (sa.startsWith("--http-adapter")) {
                // Processed by the shell
            } else if (sa.startsWith("--node-version") || sa.startsWith("--node_version")) {
                // Ignore version number -- it is processed by the shell.
            } else if ("--debug".equals(arg) || "--trace".equals(arg)) {
                // Actual processing happens elsewhere
            } else {
                throw new NodeException("Unsupported command-line option " + arg);
            }
        }
    }

    /**
     * Initialize a native module implemented in Java code.
     */
    public Object initializeModule(String modName, AbstractModuleRegistry.ModuleType type,
                                   Context cx, Scriptable scope)
        throws InvocationTargetException, InstantiationException, IllegalAccessException
    {
        NodeModule mod;
        switch (type) {
        case PUBLIC:
            mod = registry.get(modName);
            break;
        case INTERNAL:
            mod = registry.getInternal(modName);
            break;
        case NATIVE:
            mod = registry.getNative(modName);
            break;
        default:
            throw new AssertionError();
        }
        if (mod == null) {
            return null;
        }
        Object exp = mod.registerExports(cx, scope, this);
        if (exp == null) {
            throw new AssertionError("Module " + modName + " returned a null export");
        }
        return exp;
    }

    /**
     * This is used internally when one native module depends on another.
     */
    @Override
    public Object require(String modName, Context cx)
    {
        try {
            return nativeModule.internalRequire(modName, cx);
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            throw new EvaluatorException("Error initializing module: " +
                    ((targetException != null) ?
                            e.toString() + ": " + targetException.toString() :
                            e.toString()));
        } catch (InstantiationException e) {
            throw new EvaluatorException("Error initializing module: " + e.toString());
        } catch (IllegalAccessException e) {
            throw new EvaluatorException("Error initializing modugle: " + e.toString());
        }
    }

    public Object requireInternal(String modName, Context cx)
    {
        if (process == null) {
            // This might be called after a fatal initialization error.
            return null;
        }
        return process.getInternalModule(modName, cx);
    }

    /**
     * This is where we load native modules.
     */
    public boolean isNativeModule(String name)
    {
        return (registry.get(name) != null) ||
               (registry.getCompiledModule(name) != null);
    }

    /**
     * Return a module that was created implicitly or by the "native module"
     */
    public NativeModule.ModuleImpl getCachedModule(String name)
    {
        return moduleCache.get(name);
    }

    public void cacheModule(String name, NativeModule.ModuleImpl module)
    {
        moduleCache.put(name, module);
    }

    /**
     * Return a module that is used internally and exposed by "process.binding".
     */
    public Object getCachedInternalModule(String name)
    {
        return internalModuleCache.get(name);
    }

    public void cacheInternalModule(String name, Object module)
    {
        internalModuleCache.put(name, module);
    }

    private boolean startTiming(Context cx)
    {
        if (env != null) {
            long tl = env.getScriptTimeLimit();
            if (tl > 0L) {
                cx.putThreadLocal(TIMEOUT_TIMESTAMP_KEY, System.currentTimeMillis() + tl);
                return true;
            }
        }
        return false;
    }

    private void endTiming(Context cx)
    {
        cx.removeThreadLocal(TIMEOUT_TIMESTAMP_KEY);
    }

    public abstract class Activity
        implements Comparable<Activity>
    {
        protected int id;
        protected long timeout;
        protected long interval;
        protected boolean repeating;
        protected boolean cancelled;
        protected Scriptable domain;

        abstract void execute(Context cx);

        int getId() {
            return id;
        }

        void setId(int id) {
            this.id = id;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        public long getInterval() {
            return interval;
        }

        public void setInterval(long interval) {
            this.interval = interval;
        }

        public boolean isRepeating() {
            return repeating;
        }

        public void setRepeating(boolean repeating) {
            this.repeating = repeating;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        public Scriptable getDomain() {
            return domain;
        }

        public void setDomain(Scriptable domain) {
            this.domain = domain;
        }

        @Override
        public int compareTo(Activity a)
        {
            if (timeout < a.timeout) {
                return -1;
            }
            if (timeout > a.timeout) {
                return 1;
            }
            return 0;
        }
    }

    private final class Callback
        extends Activity
    {
        Function function;
        Scriptable scope;
        Scriptable thisObj;
        Object[] args;

        Callback(Function f, Scriptable s, Scriptable thisObj, Object[] args)
        {
            this.function = f;
            this.scope = s;
            this.thisObj = thisObj;
            this.args = args;
        }

        /**
         * Submit the tick, with support for domains handled in JavaScript.
         * This is also necessary because not everything that we do is a "top level function" in JS
         * and we cannot invoke those functions directly from Java code.
         */
        @Override
        void execute(Context cx)
        {
            process.submitTick(cx, args, function, thisObj, domain);
        }
    }

    private abstract class AbstractTask
        extends Activity
    {
        private Scriptable scope;

        protected AbstractTask(Scriptable scope)
        {
            this.scope = scope;
        }

        protected abstract void executeTask(Context cx);

        @Override
        void execute(Context cx)
        {
            if (domain != null) {
                if (ScriptableObject.hasProperty(domain, "_disposed")) {
                    domain = null;
                }
            }
            if (domain != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Entering domain {}", System.identityHashCode(domain));
                }
                Function enter = (Function)ScriptableObject.getProperty(domain, "enter");
                enter.call(cx, enter, domain, ScriptRuntime.emptyArgs);
            }

            executeTask(cx);

            // Do NOT do this next bit in a try..finally block. Why not? Because the exception handling
            // logic in runMain depends on "process.domain" still being set, and it will clean up
            // on failure there.
            if (domain != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Exiting domain {}", System.identityHashCode(domain));
                }
                Function exit = (Function)ScriptableObject.getProperty(domain, "exit");
                exit.call(cx, exit, domain, ScriptRuntime.emptyArgs);
            }
        }
    }

    private final class Task
        extends AbstractTask
    {
        private ScriptTask task;

        Task(ScriptTask task, Scriptable scope)
        {
            super(scope);
            this.task = task;
        }

        @Override
        protected void executeTask(Context cx)
        {
            task.execute(cx, scope);
        }
    }

    private final class RunnableTask
        extends AbstractTask
        implements Future<Boolean>
    {
        private Runnable task;

        RunnableTask(Runnable task)
        {
            super(ScriptRunner.this.scope);
            this.task = task;
        }

        @Override
        protected void executeTask(Context cx)
        {
            task.run();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning)
        {
            setCancelled(true);
            return true;
        }

        @Override
        public boolean isDone()
        {
            return false;
        }

        @Override
        public Boolean get()
        {
            return Boolean.TRUE;
        }

        @Override
        public Boolean get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
        {
            return Boolean.TRUE;
        }
    }
}
