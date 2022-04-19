package edu.utdallas.amordahl;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.config.AnalysisScopeReader;
import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;

class Application {

    //private static final Logger logger = LoggerFactory.getLogger(Application.class);

    private static CommandLineOptions clo;

    public static void main(String[] args)
            throws WalaException, CallGraphBuilderCancelException, IOException, InvalidClassFileException {
        // Initialize command line and print help if requested.
        Application.clo = new CommandLineOptions();
        new CommandLine(clo).parseArgs(args);
        if (clo.usageHelpRequested) {
            CommandLine.usage(new CommandLineOptions(), System.out);
            return;
        }

        // Build call graph.
        CallGraph cg = new Application().makeCallGraph(clo);

        // Print to output.
        FileWriter fw = new FileWriter(String.valueOf(clo.callgraphOutput));
        for (CGNode cgn : cg) {
            Iterator<CallSiteReference> callSiteIterator = cgn.iterateCallSites();
            while (callSiteIterator.hasNext()) {
                CallSiteReference csi = callSiteIterator.next();
                for (CGNode target : cg.getPossibleTargets(cgn, csi)) {
                    fw.write(String.format(
                            "%s\t%s\t%s\t%s\t%s\n",
                            cgn.getMethod(),
                            csi.toString(),
                            cgn.getContext(),
                            target.getMethod().getSignature(),
                            target.getContext()));
                }
            }
        }
        System.out.println("Wrote callgraph to " + clo.callgraphOutput.toString());
        fw.close();
    }

    public CallGraph makeCallGraph(CommandLineOptions clo)
            throws ClassHierarchyException, IOException, CallGraphBuilderCancelException {
        AnalysisScope scope =
                AnalysisScopeReader.makeJavaBinaryAnalysisScope(clo.appJar, null);

        ClassHierarchy cha = ClassHierarchyFactory.make(scope);

        Iterable<Entrypoint> entrypoints =
                com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha);
        AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
        options.setReflectionOptions(clo.reflection);
        options.setHandleStaticInit(clo.handleStaticInit);
        options.setUseConstantSpecificKeys(clo.useConstantSpecificKeys);
        options.setUseStacksForLexicalScoping(clo.useStacksForLexicalScoping);
        options.setUseLexicalScopingForGlobals(clo.useLexicalScopingForGlobals);
        options.setMaxNumberOfNodes(clo.maxNumberOfNodes);
        options.setHandleZeroLengthArray(clo.handleZeroLengthArray);

        // //
        // build the call graph
        // //
        CallGraphBuilder<InstanceKey> builder;
        switch (clo.callGraphBuilder) {
            case NCFA:
                builder = Util.makeNCFABuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha, scope);
                break;
            case NOBJ:
                builder = Util.makeNObjBuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha, scope);
                break;
            case VANILLA_NCFA:
                builder =
                        Util.makeVanillaNCFABuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha, scope);
                break;
            case VANILLA_NOBJ:
                builder =
                        Util.makeVanillaNObjBuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha, scope);
                break;
            case RTA:
                builder = Util.makeRTABuilder(options, new AnalysisCacheImpl(), cha, scope);
                break;
            case ZERO_CFA:
                builder = Util.makeZeroCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha, scope);
                break;
            case ZEROONE_CFA:
                builder = Util.makeZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha, scope);
                break;
            case VANILLA_ZEROONECFA:
                builder =
                        Util.makeVanillaZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha, scope);
                break;
            case ZEROONE_CONTAINER_CFA:
                builder = Util.makeZeroOneContainerCFABuilder(options, new AnalysisCacheImpl(), cha, scope);
                break;
            case VANILLA_ZEROONE_CONTAINER_CFA:
                builder = Util.makeVanillaZeroOneContainerCFABuilder(options, new AnalysisCacheImpl(), cha, scope);
                break;
            default:
                throw new IllegalArgumentException("Invalid call graph algorithm.");
        }
        long startTime = System.currentTimeMillis();

        MonitorUtil.IProgressMonitor pm = new MonitorUtil.IProgressMonitor() {
            private boolean cancelled;

            @Override
            public void beginTask(String s, int i) {

            }

            @Override
            public void subTask(String s) {

            }

            @Override
            public void cancel() {
                cancelled = true;
            }

            @Override
            public boolean isCanceled() {
                if (System.currentTimeMillis() - startTime > clo.timeout) {
                    cancelled = true;
                }
                return cancelled;
            }

            @Override
            public void done() {

            }

            @Override
            public void worked(int i) {

            }

            @Override
            public String getCancelMessage() {
                return "Timed out.";
            }
        };
        return builder.makeCallGraph(options, pm);
    }
}
