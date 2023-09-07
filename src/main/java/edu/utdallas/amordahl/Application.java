package edu.utdallas.amordahl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.WalaException;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

class Application {

    // private static final Logger logger =
    // LoggerFactory.getLogger(Application.class);

    private static CommandLineOptions clo;

    public static void main(final String[] args)
            throws WalaException, CallGraphBuilderCancelException, IOException {
        // Initialize command line and print help if requested.
        Application.clo = new CommandLineOptions();
        new CommandLine(Application.clo).parseArgs(args);
        if (Application.clo.usageHelpRequested) {
            CommandLine.usage(new CommandLineOptions(), System.out);
            return;
        }

        // Build call graph.
        final CallGraph cg = new Application().makeCallGraph(Application.clo);

        // Print to output.
        final FileWriter fw = new FileWriter(String.valueOf(Application.clo.callgraphOutput));
        final List<Map<String, String>> callGraph = new LinkedList<Map<String, String>>();
        for (final CGNode cgn : cg) {
            final Iterator<CallSiteReference> callSiteIterator = cgn.iterateCallSites();
            while (callSiteIterator.hasNext()) {
                final CallSiteReference csi = callSiteIterator.next();
                for (final CGNode target : cg.getPossibleTargets(cgn, csi)) {
                    final Map<String, String> callGraphEdge = new HashMap<String, String>();
                    callGraphEdge.put("caller", cgn.getMethod().getSignature());
                    callGraphEdge.put("callInstruction", csi.toString());
                    callGraphEdge.put("actualTarget", target.getMethod().getSignature());
                    callGraphEdge.put("context", target.getContext().toString());
                    callGraph.add(callGraphEdge);
                }
            }
        }
        fw.write(new ObjectMapper().writeValueAsString(callGraph));
        System.out.println("Wrote callgraph to " + Application.clo.callgraphOutput.toString());
        fw.close();
    }

    public CallGraph makeCallGraph(final CommandLineOptions clo)
            throws ClassHierarchyException, IOException, CallGraphBuilderCancelException {
        final AnalysisScope scope = AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(clo.appJar,
                new File("resources/exclusions.txt"));

        final ClassHierarchy cha = ClassHierarchyFactory.make(scope);

        final Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha);
        final AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
        options.setReflectionOptions(clo.reflection);
        options.setHandleStaticInit(!clo.disableHandleStaticInit);
        options.setUseConstantSpecificKeys(clo.useConstantSpecificKeys);
        options.setUseStacksForLexicalScoping(clo.useStacksForLexicalScoping);
        options.setUseLexicalScopingForGlobals(clo.useLexicalScopingForGlobals);
        options.setMaxNumberOfNodes(clo.maxNumberOfNodes);
        options.setHandleZeroLengthArray(!clo.disableHandleZeroLengthArray);

        // //
        // build the call graph
        // //
        CallGraphBuilder<InstanceKey> builder;
        switch (clo.callGraphBuilder) {
            case NCFA:
                builder = Util.makeNCFABuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha);
                break;
            case NOBJ:
                builder = Util.makeNObjBuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha);
                break;
            case VANILLA_NCFA:
                builder = Util.makeVanillaNCFABuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha);
                break;
            case VANILLA_NOBJ:
                builder = Util.makeVanillaNObjBuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha);
                break;
            case RTA:
                builder = Util.makeRTABuilder(options, new AnalysisCacheImpl(), cha);
                break;
            case ZERO_CFA:
                builder = Util.makeZeroCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha);
                break;
            case ZEROONE_CFA:
                builder = Util.makeZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha);
                break;
            case VANILLA_ZEROONECFA:
                builder = Util.makeVanillaZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha);
                break;
            case ZEROONE_CONTAINER_CFA:
                builder = Util.makeZeroOneContainerCFABuilder(options, new AnalysisCacheImpl(), cha);
                break;
            case VANILLA_ZEROONE_CONTAINER_CFA:
                builder = Util.makeVanillaZeroOneContainerCFABuilder(options, new AnalysisCacheImpl(), cha);
                break;
            case ZERO_CONTAINER_CFA:
                builder = Util.makeZeroContainerCFABuilder(options, new AnalysisCacheImpl(), cha);
                break;
            default:
                throw new IllegalArgumentException("Invalid call graph algorithm.");
        }
        final long startTime = System.currentTimeMillis();

        final MonitorUtil.IProgressMonitor pm = new MonitorUtil.IProgressMonitor() {
            private boolean cancelled;

            @Override
            public void beginTask(final String s, final int i) {

            }

            @Override
            public void subTask(final String s) {

            }

            @Override
            public void cancel() {
                this.cancelled = true;
            }

            @Override
            public boolean isCanceled() {
                if (System.currentTimeMillis() - startTime > clo.timeout) {
                    this.cancelled = true;
                }
                return this.cancelled;
            }

            @Override
            public void done() {

            }

            @Override
            public void worked(final int i) {

            }

            @Override
            public String getCancelMessage() {
                return "Timed out.";
            }
        };
        return builder.makeCallGraph(options, pm);
    }
}
