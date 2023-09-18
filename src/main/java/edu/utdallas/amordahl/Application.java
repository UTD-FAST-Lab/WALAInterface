package edu.utdallas.amordahl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.AllocationSite;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.cfa.*;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.WalaException;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

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
        System.out.println("WALA Property java_runtime_dir is " + Arrays.toString(WalaProperties.getJ2SEJarFiles()));

        // Build call graph.
        final CallGraph cg = new Application().makeCallGraph(Application.clo);

        // Print to output.
        List<Map<String, String>> callGraph = new LinkedList<Map<String, String>>();
        for (final CGNode callGraphNode : cg) {
            final Iterator<CallSiteReference> callSiteIterator = callGraphNode.iterateCallSites();
            while (callSiteIterator.hasNext()) {
                final CallSiteReference callSite = callSiteIterator.next();
                for (final CGNode target : cg.getPossibleTargets(callGraphNode, callSite)) {
                    final Map<String, String> callGraphEdge = new HashMap<String, String>();
                    callGraphEdge.put("caller", callGraphNode.getMethod().getSignature());
                    callGraphEdge.put("callInstruction", callSite.toString());
                    callGraphEdge.put("actualTarget", target.getMethod().getSignature());
                    List<String> contexts = new LinkedList<String>();
                    if (Application.clo.callGraphBuilder == CallGraphBuilders.NCFA) {
                        // Then we know the context is a CallString type.
                        CallString context = (CallString) target.getContext().get(CallStringContextSelector.CALL_STRING);
                        if (context != null) { // if it's null that means that it was an Everywhere context and we don't have to worry about it.
                            for (int i = 0; i < context.getMethods().length; i++) {
                                IMethod methodRef = context.getMethods()[i];
                                CallSiteReference csr = context.getCallSiteRefs()[i];
                                int realLineNumber = methodRef.getLineNumber(csr.getProgramCounter());
                                String contextString = methodRef.getDeclaringClass().getName().toString()
                                        .substring(1).replace("/", ".") + "." +
                                        methodRef.getName() + methodRef.getDescriptor() + ":" +
                                        realLineNumber;
                                contexts.add(contextString);
                            }
                        }
                    } else if (Application.clo.callGraphBuilder == CallGraphBuilders.NOBJ) {
                        AllocationString context = (AllocationString) target.getContext().get(nObjContextSelector.ALLOCATION_STRING_KEY);
                        if (context != null) {
                            for (int i = 0; i < context.getAllocationSites().length; i++) {
                                AllocationSite as = context.getAllocationSites()[i];
                                String contextString = as.getMethod().getDeclaringClass().getName().toString()
                                        .substring(1).replace("/",".") + "." +
                                        as.getMethod().getName() + as.getMethod().getDescriptor() + ":" +
                                        as.getMethod().getLineNumber(as.getSite().getProgramCounter());
                                contexts.add(contextString);
                            }
                        }
                    }
                    callGraphEdge.put("contexts", contexts.toString());
                    //System.out.println("Context list is " + contexts.toString());
                    callGraph.add(callGraphEdge);
                }
            }
        }
        // Break up call graph into multiple files, in order to prevent really big files.
        int iteration = 0;
        int intervalSize = 1000000;
        if (callGraph.size() < intervalSize) {
            writeChunkToFile(callGraph, Application.clo.callgraphOutput.toString());
            System.out.println("Wrote callgraph to " + Application.clo.callgraphOutput.toString());
        } else {
            System.out.println("Writing in chunks of " + intervalSize + " in order to prevent huge files.");
            while (callGraph.size() >= intervalSize) {
                List<Map<String, String>> chunk = callGraph.subList(0, intervalSize);
                callGraph = callGraph.subList(intervalSize, callGraph.size());
                String file = Application.clo.callgraphOutput.toString() + iteration;
                writeChunkToFile(chunk, Application.clo.callgraphOutput.toString() + iteration);
                System.out.println("Wrote chunk of callgraph to " + file);
                iteration += 1;
            }
            writeChunkToFile(callGraph, Application.clo.callgraphOutput.toString() + iteration);
        }
    }

    private static void writeChunkToFile(List<Map<String, String>> chunk, String fileName) throws IOException {
        final FileWriter fw = new FileWriter(fileName);
        ObjectMapper om = new ObjectMapper();
        fw.write(om.writerWithDefaultPrettyPrinter().writeValueAsString(chunk));
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
