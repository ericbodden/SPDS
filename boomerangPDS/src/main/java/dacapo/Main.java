package dacapo;

import java.io.File;
import java.io.IOException;

public class Main {
	// jython and haqldb fail on WALA (no callgraph can be computed)
//	static String[] dacapo = new String[] {  "lusearch"};
//	static String[] dacapo = new String[] {  "luindex" };
	static String[] dacapo = new String[] { "antlr", "chart", "eclipse",
			 "jython","hsqldb", "luindex", "lusearch", "pmd", "xalan",  "bloat" };

	//Memory: chart, Stackoverflow: jython
	static String benchmarkFolder = "/Users/johannesspath/Documents/dacapo/";

	public static void main(String... args) {
		runAnalysis();
	}

	private static void runAnalysis() {
		for (String bench : dacapo) {
			String javaHome = System.getProperty("java.home");
			String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
			ProcessBuilder builder = new ProcessBuilder(new String[] { javaBin, "-Xmx14g", "-Xms14g","-Xss128m", "-cp",
					System.getProperty("java.class.path"), DacapoRunner.class.getName(),
					benchmarkFolder, bench});
			builder.inheritIO();

			Process process;
			try {
				process = builder.start();
				process.waitFor();
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

}
