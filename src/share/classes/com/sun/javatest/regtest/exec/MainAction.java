/*
 * Copyright (c) 1998, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.javatest.regtest.exec;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.sun.javatest.Status;
import com.sun.javatest.regtest.TimeoutHandler;
import com.sun.javatest.regtest.agent.MainWrapper;
import com.sun.javatest.regtest.agent.SearchPath;
import com.sun.javatest.regtest.config.ExecMode;
import com.sun.javatest.regtest.config.JDK;
import com.sun.javatest.regtest.config.JDKOpts;
import com.sun.javatest.regtest.config.Locations.LibLocn;
import com.sun.javatest.regtest.config.Modules;
import com.sun.javatest.regtest.config.ParseException;
import com.sun.javatest.regtest.exec.RegressionScript.PathKind;
import com.sun.javatest.regtest.util.StringUtils;

import static com.sun.javatest.regtest.agent.RStatus.createStatus;
import static com.sun.javatest.regtest.agent.RStatus.error;
import static com.sun.javatest.regtest.agent.RStatus.failed;
import static com.sun.javatest.regtest.agent.RStatus.normalize;
import static com.sun.javatest.regtest.agent.RStatus.passed;

/**
 * This class implements the "main" action as described by the JDK tag
 * specification.
 *
 * @author Iris A Garcia
 * @see Action
 * @see com.sun.javatest.regtest.agent.MainActionHelper
 */
public class MainAction extends Action
{
    public static final String NAME = "main";

    /**
     * {@inheritDoc}
     * @return "main"
     */
    @Override
    public String getName() {
        return NAME;
    }


    /**
     * This method does initial processing of the options and arguments for the
     * action.  Processing is determined by the requirements of run().
     *
     * Verify arguments are not of length 0 and separate them into the options
     * to java, the classname, and the parameters to the named class.
     *
     * Verify that the options are valid for the "main" action.
     *
     * @param opts The options for the action.
     * @param args The arguments for the actions.
     * @param reason Indication of why this action was invoked.
     * @param script The script.
     * @exception  ParseException If the options or arguments are not expected
     *             for the action or are improperly formated.
     */
    @Override
    public void init(Map<String,String> opts, List<String> args, String reason,
                     RegressionScript script)
        throws ParseException
    {
        init(opts, args, reason, script, null);
    }

    /**
     * Local version of public init function.
     * Supports extra driverClass option, to interpose before main class.
     * @param driverClass actual class to invoke, with main class as first argument
     */
    void init(Map<String,String> opts, List<String> args, String reason,
                     RegressionScript script,
                     String driverClass)
        throws ParseException
    {
        super.init(opts, args, reason, script);

        if (args.isEmpty())
            throw new ParseException(MAIN_NO_CLASSNAME);

        boolean othervm = false;

        for (Map.Entry<String,String> e: opts.entrySet()) {
            String optName  = e.getKey();
            String optValue = e.getValue();

            switch (optName) {
                case "fail":
                    reverseStatus = parseFail(optValue);
                    break;
                case "manual":
                    manual = parseMainManual(optValue);
                    break;
                case "timeout":
                    timeout  = parseTimeout(optValue);
                    break;
                case "othervm":
                    othervm = true;
                    othervmOverrideReasons.add("/othervm specified");
                    break;
                case "native":
                    nativeCode = true;
                    break;
                case "bootclasspath":
                    useBootClassPath = true;
                    othervmOverrideReasons.add("/bootclasspath specified");
                    break;
                case "policy":
                    overrideSysPolicy = true;
                    policyFN = parsePolicy(optValue);
                    break;
                case "java.security.policy":
                    String name = optValue;
                    if (optValue.startsWith("=")) {
                        overrideSysPolicy = true;
                        name = optValue.substring(1, optValue.length());
                    }   policyFN = parsePolicy(name);
                    break;
                case "secure":
                    secureCN = parseSecure(optValue);
                    break;
                default:
                    throw new ParseException(MAIN_BAD_OPT + optName);
            }
        }

        if (manual.equals("unset")) {
            if (timeout < 0)
                timeout = script.getActionTimeout(-1);
        } else {
            if (timeout >= 0)
                // can't have both timeout and manual
                throw new ParseException(PARSE_TIMEOUT_MANUAL);
            timeout = 0;
        }

        if (driverClass != null) {
            this.driverClass = driverClass;
        }

        if (script.useBootClassPath()) {
            useBootClassPath = true;
            othervmOverrideReasons.add("test or library uses bootclasspath");
        }

        // separate the arguments into the options to java, the
        // classname and the parameters to the named class
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (testClassName == null) {
                if (arg.startsWith("-")) {
                    testJavaArgs.add(arg);
                    if (JDKOpts.hasFollowingArg(arg)) {
                        testJavaArgs.add(args.get(++i));
                    }
                } else {
                    int sep = arg.indexOf("/");
                    if (sep == -1) {
                        testModuleName = null;
                        testClassName = arg;
                    } else {
                        testModuleName = arg.substring(0, sep);
                        testClassName = arg.substring(sep + 1);
                    }
                }
            } else {
                testClassArgs.add(arg);
            }
        }

        if (testClassName == null)
            throw new ParseException(MAIN_NO_CLASSNAME);

        if (!othervm) {
            // None of these need be fatal any more: we could just automatically
            // set othervm mode if any of the following conditions arise.
            if (testJavaArgs.size() > 0)
                throw new ParseException(testJavaArgs + MAIN_UNEXPECT_VMOPT);
            if (policyFN != null)
                throw new ParseException(PARSE_POLICY_OTHERVM);
            if (secureCN != null)
                throw new ParseException(PARSE_SECURE_OTHERVM);
        }

        if (!othervm && !useModuleExportAPI) {
            for (Modules.Entry e: script.getModules()) {
                if (e.packageName != null) {
                    String arg = e.moduleName + "/" + e.packageName + "=ALL-UNNAMED";
                    if (e.addExports && !includesOption("--add-exports", arg, script.getTestVMJavaOptions())) {
                        othervmOverrideReasons.add("test needs --add-exports");
                        break;
                    }
                    if (e.addOpens && !includesOption("--add-opens", arg, script.getTestVMJavaOptions())) {
                        othervmOverrideReasons.add("test needs --add-opens");
                        break;
                    }
                }
            }
        }

        if (!othervm) {
            for (Modules.Entry e: script.getModules()) {
                if (!script.defaultModules.contains(e.moduleName)) {
                    othervmOverrideReasons.add("test needs --add-modules");
                    break;
                }
            }
        }
    } // init()

    public List<String> getJavaArgs() {
        return testJavaArgs;
    }
    public String getModuleName() {
        return testModuleName;
    }
    public String getClassName() {
        return testClassName;
    }
    public List<String> getClassArgs() {
        return testClassArgs;
    }

    List<String> filterJavaOpts(List<String> args) {
        return args;
    }

    @Override
    public Set<File> getSourceFiles() {
        Set<File> files = new LinkedHashSet<>();
        if (testClassName != null) {
            Map<String,String> buildOpts = Collections.emptyMap();
            List<String> buildArgs = Arrays.asList(join(testModuleName, testClassName));
            try {
                BuildAction ba = new BuildAction();
                ba.init(buildOpts, buildArgs, SREASON_ASSUMED_BUILD, script);
                files.addAll(ba.getSourceFiles());
            } catch (ParseException ignore) {
            }
        }
        if (policyFN != null)
            files.add(new File(policyFN));
        return files;
    }

    @Override
    public Set<String> getModules() {
        return (testModuleName == null)
                ? Collections.<String>emptySet()
                : Collections.singleton(testModuleName);
    }

    /**
     * The method that does the work of the action.  The necessary work for the
     * given action is defined by the tag specification.
     *
     * Invoke the main method of the specified class, passing any arguments
     * after the class name.  A "main" action is considered to be finished when
     * the main method returns.
     *
     * A "main" action passes if the main method returns normally and does not
     * cause an exception to be thrown by the main or any subsidiary threads.
     * It fails otherwise.
     *
     * @return     The result of the action.
     * @exception  TestRunException If an unexpected error occurs while running
     *             the test.
     */
    @Override
    public Status run() throws TestRunException {
        if (script.useXpatch())
            othervmOverrideReasons.add("test or library overrides a system module");

        Status status;

        if (!(status = build()).isPassed())
            return status;

        if (nativeCode && script.getNativeDir() == null)
            return error(MAIN_NO_NATIVES);

        startAction(true);

        if (script.isCheck()) {
            status = passed(CHECK_PASS);
        } else {
            Lock lock = script.getLockIfRequired();
            if (lock != null) lock.lock();
            try {
                switch (!othervmOverrideReasons.isEmpty() ? ExecMode.OTHERVM : script.getExecMode()) {
                    case AGENTVM:
                        showMode(ExecMode.AGENTVM);
                        status = runAgentJVM();
                        break;
                    case OTHERVM:
                        showMode(ExecMode.OTHERVM, othervmOverrideReasons);
                        status = runOtherJVM();
                        break;
                    default:
                        throw new AssertionError();
                }
            } finally {
                if (lock != null) lock.unlock();
            }
        }

        endAction(status);
        return status;
    } // run()

    //----------internal methods------------------------------------------------

    protected Status build() throws TestRunException {
        // TAG-SPEC:  "The named <class> will be compiled on demand, just as
        // though an "@run build <class>" action had been inserted before
        // this action."
        Map<String,String> buildOpts = Collections.emptyMap();
        List<String> buildArgs = Arrays.asList(join(testModuleName, testClassName));
        BuildAction ba = new BuildAction();
        return ba.build(buildOpts, buildArgs, SREASON_ASSUMED_BUILD, script);
    }

    private Status runOtherJVM() throws TestRunException {
        // Arguments to wrapper:
        String runModuleName;
        String runModuleClassName;
        List<String> runClassArgs;
        if (driverClass == null) {
            runModuleName = testModuleName;
            runModuleClassName = join(testModuleName, testClassName);
            runClassArgs = testClassArgs;
        } else {
            runModuleName = null;
            runModuleClassName = driverClass;
            runClassArgs = new ArrayList<>();
            runClassArgs.add(script.getTestResult().getTestName());
            runClassArgs.add(join(testModuleName, testClassName));
            runClassArgs.addAll(testClassArgs);
        }

        // WRITE ARGUMENT FILE
        File argFile = getArgFile();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(argFile))) {
            w.write(runModuleClassName + "\0");
            w.write(StringUtils.join(runClassArgs, " ") + "\0");
        } catch (IOException e) {
            return error(MAIN_CANT_WRITE_ARGS);
        }

        // CONSTRUCT THE COMMAND LINE

        // TAG-SPEC:  "The source and class directories of a test are made
        // available to main and applet actions via the system properties
        // "test.src" and "test.classes", respectively"
        Map<String, String> env = new LinkedHashMap<>();
        env.putAll(script.getEnvVars());

        // some tests are inappropriately relying on the CLASSPATH environment
        // variable being set, so force the use here.
        final boolean useCLASSPATH = true;

        Set<LibLocn.Kind> testKinds = script.locations.getDirKinds(script.locations.absTestSrcDir());
        boolean multiModule = testKinds.equals(EnumSet.of(LibLocn.Kind.USER_MODULE));

        Map<PathKind, SearchPath> paths =
                script.getExecutionPaths(multiModule, runModuleName, useBootClassPath, true);

        SearchPath cp = paths.get(PathKind.CLASSPATH);
        if (useCLASSPATH && (cp != null) && !cp.isEmpty()) {
            env.put("CLASSPATH", cp.toString());
        }

        String javaCmd = script.getJavaProg();
        JDKOpts javaOpts = new JDKOpts();

        if (!useCLASSPATH) {
            javaOpts.addPath("--class-path", cp);
        }

        SearchPath bcpa = paths.get(PathKind.BOOTCLASSPATH_APPEND);
        SearchPath pp = paths.get(PathKind.PATCHPATH);
        javaOpts.addPath("-Xbootclasspath/a:", bcpa);
        javaOpts.addAllXPatch(pp);
        javaOpts.addPath("--module-path", paths.get(PathKind.MODULEPATH));

        Set<String> addMods = new LinkedHashSet<>();
        if (testModuleName != null)
            addMods.add(testModuleName);
        addMods.addAll(getModules(paths.get(PathKind.MODULEPATH)));
        if (!addMods.isEmpty()) {
            javaOpts.add("--add-modules");
            javaOpts.add(StringUtils.join(addMods, ","));
        }

        if (pp != null && !pp.isEmpty() && bcpa != null && !bcpa.isEmpty()) {
            // provide --add-reads from patch modules to unnamed module(s).
            for (String s: getModules(pp)) {
                javaOpts.add("--add-reads=" + s + "=ALL-UNNAMED");
            }
        }

        javaOpts.addAll(getExtraModuleConfigOptions(Modules.Phase.DYNAMIC));
        javaOpts.addAll(script.getTestVMJavaOptions());
        javaOpts.addAll(script.getTestDebugOptions());

        Map<String, String> javaProps = new LinkedHashMap<>();
        javaProps.putAll(script.getTestProperties());

        String newPolicyFN;
        if (policyFN != null) {
            // add permission to read JTwork/classes by adding a grant entry
            newPolicyFN = addGrantEntry(policyFN);
            javaProps.put("java.security.policy",
                          overrideSysPolicy ? "=" + newPolicyFN : newPolicyFN);
        }

        if (secureCN != null) {
            javaProps.put("java.security.manager", secureCN);
        }
        else if (policyFN != null) {
            javaProps.put("java.security.manager", "default");
        }
//      javaProps.put("java.security.debug", "all");

        javaOpts.addAll(testJavaArgs);

        String className = MainWrapper.class.getName();
        List<String> classArgs = new ArrayList<>();
        classArgs.add(argFile.getPath());

        classArgs.addAll(runClassArgs);

        List<String> command = new ArrayList<>();
        command.add(javaCmd);
        for (Map.Entry<String,String> e: javaProps.entrySet())
            command.add("-D" + e.getKey() + "=" + e.getValue());
        command.addAll(filterJavaOpts(javaOpts.toList()));
        command.add(className);
        command.addAll(classArgs);

        // PASS TO PROCESSCOMMAND
        Status status;
        PrintWriter sysOut = section.createOutput("System.out");
        PrintWriter sysErr = section.createOutput("System.err");
        try {
            if (showMode)
                showMode(getName(), ExecMode.OTHERVM, section);
            if (showCmd)
                showCmd(getName(), command, section);
            new ModuleConfig("Boot Layer").setFromOpts(javaOpts).write(configWriter);
            recorder.java(env, javaCmd, javaProps, javaOpts.toList(), className, classArgs);

            // RUN THE MAIN WRAPPER CLASS
            ProcessCommand cmd = new ProcessCommand();
            cmd.setExecDir(script.absTestScratchDir());

            // Set the exit codes and their associated strings.  Note that we
            // require the use of a non-zero exit code for a passed test so
            // that we have a chance of detecting whether the test itself has
            // illegally called System.exit(0).
            cmd.setStatusForExit(Status.exitCodes[Status.PASSED], passed(EXEC_PASS));
            cmd.setStatusForExit(Status.exitCodes[Status.FAILED], failed(EXEC_FAIL));
            cmd.setDefaultStatus(failed(UNEXPECT_SYS_EXIT));

            TimeoutHandler timeoutHandler =
                script.getTimeoutHandlerProvider().createHandler(script, section);

            cmd.setCommand(command)
                .setEnvironment(env)
                .setStreams(sysOut, sysErr)
                .setTimeout(timeout, TimeUnit.SECONDS)
                .setTimeoutHandler(timeoutHandler);

            status = normalize(cmd.exec());

        } finally {
            sysOut.close();
            sysErr.close();
        }

        // EVALUATE THE RESULTS
        status = checkReverse(status, reverseStatus);

        return status;
    } // runOtherJVM()

    private Status runAgentJVM() throws TestRunException {
        String runModuleName;
        String runMainClass;
        List<String> runMainArgs;
        if (driverClass == null) {
            runModuleName = testModuleName;
            runMainClass = testClassName;
            runMainArgs = testClassArgs;
        } else {
            runModuleName = null;
            runMainClass = driverClass;
            runMainArgs = new ArrayList<>();
            runMainArgs.add(script.getTestResult().getTestName());
            runMainArgs.add(testClassName);
            runMainArgs.addAll(testClassArgs);
        }

        Set<LibLocn.Kind> testKinds = script.locations.getDirKinds(script.locations.absTestSrcDir());
        boolean multiModule = testKinds.equals(EnumSet.of(LibLocn.Kind.USER_MODULE));

        Map<PathKind, SearchPath> paths =
                script.getExecutionPaths(multiModule, runModuleName, useBootClassPath, true);

        JDK jdk = script.getTestJDK();
        List<File> stdLibs = new SearchPath()
                .append(script.getJavaTestClassPath())
                .append(jdk.getJDKClassPath())
                .append(script.getJUnitPath())
                .append(script.getTestNGPath())
                .asList();

        SearchPath classpath = paths.get(PathKind.CLASSPATH);
        SearchPath agentClasspath = new SearchPath(classpath).retainAll(stdLibs);
        SearchPath runClasspath = new SearchPath(classpath).removeAll(stdLibs);

        if (showMode)
            showMode(getName(), ExecMode.AGENTVM, section);

        // TAG-SPEC:  "The source and class directories of a test are made
        // available to main and applet actions via the system properties
        // "test.src" and "test.classes", respectively"
        Map<String, String> javaProps = script.getTestProperties();

        String javaProg = script.getJavaProg();
        List<String> javaArgs = Arrays.asList("-classpath", classpath.toString());
        recorder.java(script.getEnvVars(), javaProg, javaProps, javaArgs, runMainClass, runMainArgs);

        Agent agent;
        try {
            agent = script.getAgent(jdk, agentClasspath,
                    filterJavaOpts(join(script.getTestVMJavaOptions(), script.getTestDebugOptions())));
            new ModuleConfig("Boot Layer").setFromOpts(agent.vmOpts).write(configWriter);
        } catch (Agent.Fault e) {
            return error(AGENTVM_CANT_GET_VM + ": " + e.getCause());
        }

        TimeoutHandler timeoutHandler =
                script.getTimeoutHandlerProvider().createHandler(script, section);

        Status status;
        try {
            Set<String> runAddExports = new LinkedHashSet<>();
            Set<String> runAddOpens = new LinkedHashSet<>();
            if (jdk.hasModules()) {
                for (Modules.Entry e : script.getModules()) {
                    if (e.packageName != null) {
                        if (e.addExports) {
                            runAddExports.add(e.moduleName + "/" + e.packageName);
                        }
                        if (e.addOpens) {
                            runAddOpens.add(e.moduleName + "/" + e.packageName);
                        }
                    }
                }
            }

            if (!runAddExports.isEmpty()) {
                StringBuilder sb = null;
                for (String s : runAddExports) {
                    if (s.contains("/")) {
                        if (sb == null) {
                            sb = new StringBuilder();
                            sb.append("Additional exports to unnamed modules from @modules: ");
                        } else {
                            sb.append(" ");
                        }
                        sb.append(s);
                    }
                }
                if (sb != null) {
                    section.getMessageWriter().println(sb);
                }
            }

            if (!runAddOpens.isEmpty()) {
                StringBuilder sb = null;
                for (String s : runAddOpens) {
                    if (s.contains("/")) {
                        if (sb == null) {
                            sb = new StringBuilder();
                            sb.append("Additional opens to unnamed modules from @modules: ");
                        } else {
                            sb.append(" ");
                        }
                        sb.append(s);
                    }
                }
                if (sb != null) {
                    section.getMessageWriter().println(sb);
                }
            }

            new ModuleConfig("Test Layer")
                    .setAddExportsToUnnamed(runAddExports)
                    .setAddOpensToUnnamed(runAddOpens)
                    .setClassPath(runClasspath)
                    .write(configWriter);

            // This calls through to MainActionHelper.runClass
            status = agent.doMainAction(
                    script.getTestResult().getTestName(),
                    javaProps,
                    runAddExports,
                    runAddOpens,
                    runClasspath,
                    runMainClass,
                    runMainArgs,
                    timeout,
                    timeoutHandler,
                    section);
        } catch (Agent.Fault e) {
            if (e.getCause() instanceof IOException)
                status = error(String.format(AGENTVM_IO_EXCEPTION, e.getCause()));
            else
                status = error(String.format(AGENTVM_EXCEPTION, e.getCause()));
        }
        if (status.isError()) {
            script.closeAgent(agent);
        }

        // EVALUATE THE RESULTS
        status = checkReverse(status, reverseStatus);

        return status;
    } // runAgentJVM()


    //----------utility methods-------------------------------------------------

    private String parseMainManual(String value) throws ParseException {
        if (value != null)
            throw new ParseException(MAIN_MANUAL_NO_VAL + value);
        else
            value = "novalue";
        return value;
    } // parseMainManual()

    private Status checkReverse(Status status, boolean reverseStatus) {
        // The standard rule is that /fail will invert Passed and Failed results
        // but will leave Error results alone.  But, for historical reasons
        // perpetuated by the Basic test program, a test calling System.exit
        // is reported with a Failed result, whereas Error would really be
        // more appropriate.  Therefore, we take care not to invert the
        // status if System.exit was called to exit the test.
        if (!status.isError()
                && !status.getReason().startsWith(UNEXPECT_SYS_EXIT)) {
            boolean ok = status.isPassed();
            int st = status.getType();
            String sr;
            if (ok && reverseStatus) {
                sr = EXEC_PASS_UNEXPECT;
                st = Status.FAILED;
            } else if (ok && !reverseStatus) {
                sr = EXEC_PASS;
            } else if (!ok && reverseStatus) {
                sr = EXEC_FAIL_EXPECT;
                st = Status.PASSED;
            } else { /* !ok && !reverseStatus */
                sr = EXEC_FAIL;
            }
            if ((st == Status.FAILED) && ! (status.getReason() == null) &&
                    !status.getReason().equals(EXEC_PASS))
                sr += ": " + status.getReason();
            status = createStatus(st, sr);
        }

        return status;
    }

    private String join(String moduleName, String className) {
        return (moduleName == null) ? className : moduleName + '/' + className;
    }


    //----------member variables------------------------------------------------

    private final List<String>  testJavaArgs = new ArrayList<>();
    private final List<String>  testClassArgs = new ArrayList<>();
    private String  driverClass = null;
    private String  testModuleName  = null;
    private String  testClassName  = null;
    private String  policyFN = null;
    private String  secureCN = null;
    private boolean overrideSysPolicy = false;

    protected boolean reverseStatus = false;
    protected boolean useBootClassPath = false;
    protected Set<String> othervmOverrideReasons = new LinkedHashSet<>();
    protected boolean nativeCode = false;
    private int     timeout = -1;
    private String  manual  = "unset";

    // No longer necessary?
    private static final boolean useModuleExportAPI = true; // config("useModuleExportAPI");
}