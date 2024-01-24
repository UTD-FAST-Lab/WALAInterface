package edu.utdallas.amordahl;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;

class Application {

    //private static final Logger logger = LoggerFactory.getLogger(Application.class);

    private static CommandLineOptions clo;

    public static void main(String[] args)
            throws WalaException, CallGraphBuilderCancelException, IOException {
        // Initialize command line and print help if requested.
        Application.clo = new CommandLineOptions();
        new CommandLine(clo).parseArgs(args);
        if (clo.usageHelpRequested) {
            CommandLine.usage(new CommandLineOptions(), System.out);
            return;
        }

        // Build call graph.
        CallGraph cg = new Application().makeCallGraph(clo);

        Map<String, List<String>> keyValuesMap = new HashMap<>();
        // Print to output.
        for (CGNode cgn : cg) {
            Iterator<CallSiteReference> callSiteIterator = cgn.iterateCallSites();
            while (callSiteIterator.hasNext()) {
                CallSiteReference csi = callSiteIterator.next();
                for (CGNode target : cg.getPossibleTargets(cgn, csi)) {
                    try {
                        addValue(keyValuesMap, cgn.getMethod().toString(), target.getMethod().getSignature());
                    } catch (NullPointerException e) {
                        System.err.println("Could not process node " + csi.toString());
                    }
                }
            }
        }
        convertHashMapToJson(keyValuesMap, clo.callgraphOutput.toString());
        System.out.println("Wrote callgraph to " + clo.callgraphOutput.toString());
    }

    private static void addValue(Map<String, List<String>> map, String key, String value) {
        // If the key is not present, create a new list
        map.putIfAbsent(key, new ArrayList<>());
        // Add the value to the list associated with the key
        map.get(key).add(value);
    }

    private static void convertHashMapToJson(Map<String, List<String>> map, String output) {
        try {
            // Create an ObjectMapper
            ObjectMapper objectMapper = new ObjectMapper();

            // Convert the HashMap to JSON string
            String jsonString = objectMapper.writeValueAsString(map);

            // Convert JSON string to a JSON object (JsonNode)
            Object jsonNode = objectMapper.readValue(jsonString, Object.class);
            objectMapper.writeValue(new File(output), jsonNode);

        } catch (IOException e) {
            // Handle exception if necessary
            e.printStackTrace();
        }
    }

    public CallGraph makeCallGraph(CommandLineOptions clo)
            throws ClassHierarchyException, IOException, CallGraphBuilderCancelException {
                
        AnalysisScope scope =
                AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(clo.appJar, null);
                

        ClassHierarchy cha = ClassHierarchyFactory.make(scope);

        Iterable<Entrypoint> entrypoints =
                com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha);
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
                builder = Util.makeNCFABuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha);
                break;
            case NOBJ:
                builder = Util.makeNObjBuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha);
                break;
            case VANILLA_NCFA:
                builder =
                        Util.makeVanillaNCFABuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha);
                break;
            case VANILLA_NOBJ:
                builder =
                        Util.makeVanillaNObjBuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha);
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
                builder =
                        Util.makeVanillaZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha);
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
