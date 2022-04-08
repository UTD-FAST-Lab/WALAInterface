package edu.utdallas.amordahl;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.JarFileEntry;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.impl.ExplicitCallGraph;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import picocli.CommandLine;

class Application {

    private String jarFile;

    public static void main(String[] args)
            throws WalaException, CallGraphBuilderCancelException, IOException, InvalidClassFileException {
        CommandLineOptions clo = new CommandLineOptions();
        new CommandLine(clo).parseArgs(args);
        CallGraph cg = new Application().makeCallGraph(clo);

        FileWriter fw = new FileWriter(String.valueOf(clo.callgraphOutput));
        fw.write("caller\tcallsite\tcalling_context\ttarget\ttarget_context\n");
        for (CGNode cgn : cg) {
            Iterator<CallSiteReference> callSiteIterator = cgn.iterateCallSites();
            if (!cgn.getMethod().isWalaSynthetic()) {
                while (callSiteIterator.hasNext()) {
                    CallSiteReference csi = callSiteIterator.next();
                    for (CGNode target : cg.getPossibleTargets(cgn, csi)) {
                        int lineNumber = getLineNumber(cgn, csi);
                        String sourceLine = Application.getSourceCodeLine(lineNumber, cgn, clo.appJar);
                        String outLine = sourceLine == null ? csi.toString() : sourceLine;
                        fw.write(String.format(
                                        "%s\t%s\t%s\t%s\t%s\n",
                                        cgn.getMethod(),
                                        outLine,
                                        cgn.getContext(),
                                        target.getMethod(),
                                        target.getContext()));
                    }
                }
            }
        }
        fw.close();
    }

    private static int getLineNumber(CGNode cgn, CallSiteReference csi) throws InvalidClassFileException {
        // Get the IR object associated with the CGNode that contains the call site reference.
        IR ir = ((ExplicitCallGraph.ExplicitNode)(cgn)).getIR();
        for (SSAInstruction s : ir.getInstructions()) {
            if (!cgn.getMethod().isWalaSynthetic()) { // We don't care about WALA's synthetic methods
                String site = null;
                if (s instanceof SSANewInstruction) {
                    site = ((SSANewInstruction) s).getNewSite().toString();
                } else if (s instanceof SSAInvokeInstruction) {
                    site = ((SSAInvokeInstruction) s).getCallSite().toString();
                }
                if (site != null && site.equals(csi.toString())) {
                    System.out.println("Site");
                    return ir.getMethod().getLineNumber(((IBytecodeMethod)ir.getMethod()).getBytecodeIndex(s.iIndex()));
                }
            }
        }
        return -1;
    }

    private static String getSourceCodeLine(int lineNumber, CGNode cgn, String jarFile) throws IOException {
       try {
           String fileName = cgn.getMethod().getDeclaringClass().getSourceFileName();
           JarFile jf = new JarFile(jarFile);
           ZipEntry sourceFile = jf.getEntry(fileName);
           InputStream is = jf.getInputStream(sourceFile);
           List<String> br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
           return br.get(lineNumber - 1);
       } catch (IOException ie) {
           return null;
       }
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
