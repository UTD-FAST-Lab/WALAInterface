package edu.utdallas.amordahl;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.util.WalaException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import picocli.CommandLine;

class Application {

    public static void main(String[] args)
            throws WalaException, CallGraphBuilderCancelException, IOException {
        CommandLineOptions clo = new CommandLineOptions();
        new CommandLine(clo).parseArgs(args);
        CallGraph cg = new Application().makeCallGraph(clo);

        FileWriter fw = new FileWriter(String.valueOf(clo.callgraphOutput));
        fw.write("caller\tcallsite\tcalling_context\ttarget\ttarget_context\n");
        for (CGNode cgn : cg) {
            Iterator<CallSiteReference> callSiteIterator = cgn.iterateCallSites();
            while (callSiteIterator.hasNext()) {
                CallSiteReference csi = callSiteIterator.next();
                for (CGNode target : cg.getPossibleTargets(cgn, csi)) {
                    fw.write(
                            String.format(
                                    "%s\t%s\t%s\t%s\t%s\n",
                                    cgn.getMethod(),
                                    csi.toString(),
                                    cgn.getContext(),
                                    target.getMethod(),
                                    target.getContext()));
                }
            }
        }
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
                builder = Util.makeNObjBuilder(clo.sensitivity, options, new AnalysisCacheImpl(), cha,scope);
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
        CallGraph cg = builder.makeCallGraph(options, null);
        return cg;
    }
}
