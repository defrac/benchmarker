package defrac.benchmarker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Main {
  static String[] BENCHMARKS = {
      "DeltaBlue",
      "FluidMotion",
      "Richards",
      "Tracer",
      "Havlak"
  };

  static String DEFRAC_BENCHMARKS = ".";
  static String TON80_BENCHMARKS = ".";
  static String PATH_TO_DEFRAC = "defrac";
  static String PATH_TO_JAVA = "java";
  static String PATH_TO_DART = "dart";
  static String PATH_TO_DART2JS = "dart2js";
  static String PATH_TO_D8 = "d8";
  static String PATH_TO_JS = "js";

  static boolean IS_LINUX =
      System.getProperty("os.name", "").toLowerCase().contains("linux");

  static String[] DEFRAC_PLATFORMS =
      IS_LINUX
          ? new String[] { "jvm", "web", "linux" }
          : new String[] { "jvm", "web" };

  static DartRunner DART_RUNNER = new DartRunner();
  static List<Runner> DART2JS_RUNNERS = Arrays.asList(new Dart2JSRunners.D8(), new Dart2JSRunners.SpiderMonkey());
  static List<Runner> JS_RUNNERS = Arrays.asList(new JSRunners.D8(), new JSRunners.SpiderMonkey());
  static Map<String, List<? extends Runner>> DEFRAC_RUNNERS = new HashMap<>();

  static {
    DEFRAC_RUNNERS.put("jvm", Arrays.asList(new DefracRunners.JVM()));
    DEFRAC_RUNNERS.put("web", Arrays.asList(new DefracRunners.D8(), new DefracRunners.SpiderMonkey()));
    DEFRAC_RUNNERS.put("linux", Arrays.asList(new DefracRunners.Linux()));
  }

  static Map<String, List<Result>> RESULTS = new HashMap<>();

  static int maxRunnerNameLength = 0;

  static PrintWriter stdout, stderr;

  public static void main(String[] args) {
    try {
      for(int i = 0; i < args.length; ++i) {
        switch (args[i].toLowerCase()) {
          case "--defrac-benchmarks": DEFRAC_BENCHMARKS = args[++i]; break;
          case "--ton80-benchmarks": TON80_BENCHMARKS = args[++i]; break;
          case "--d8": PATH_TO_D8 = args[++i]; break;
          case "--dart": PATH_TO_DART = args[++i]; break;
          case "--dart2js": PATH_TO_DART2JS = args[++i]; break;
          case "--defrac": PATH_TO_DEFRAC= args[++i]; break;
          case "--java": PATH_TO_JAVA = args[++i]; break;
          case "--js": PATH_TO_JS = args[++i]; break;
          case "-help": case "-h":case "/?":
          case "--help":
            System.out.println("--defrac-benchmarks\tPath to defrac benchmark suite");
            System.out.println("--ton80-benchmarks\tPath to ton80 benchmark suite");
            System.out.println("--d8\tPath to V8");
            System.out.println("--dart\tPath to dart");
            System.out.println("--dart2js\tPath to dart2js");
            System.out.println("--defrac\tPath to defrac");
            System.out.println("--java\tPath to java");
            System.out.println("--js\tPath to SpiderMonkey");
            System.exit(0);
            return;
          default:
            System.out.println("Unknown option \""+args[i]+'"');
            System.out.println("Use --help to show options");
            System.exit(2);
            return;
        }
      }
    } catch(Exception e) {
      System.out.println("Usage: java -jar benchmarker.jar [options]");
      System.out.println("Use --help to show options");
      System.exit(2);
      return;
    }

    final Path stdoutPath = Paths.get("stdout.txt");
    final Path stderrPath = Paths.get("stderr.txt");

    try {
      Files.deleteIfExists(stdoutPath);
      Files.deleteIfExists(stderrPath);
    } catch(final IOException ioException) {
      System.err.println("Couldn't delete output file");
      System.exit(1);
    }

    try(PrintWriter out = new PrintWriter(Files.newBufferedWriter(stdoutPath, StandardCharsets.UTF_8));
        PrintWriter err = new PrintWriter(Files.newBufferedWriter(stderrPath, StandardCharsets.UTF_8))) {
      stdout = out;
      stderr = err;
      benchmark();
      System.exit(0);//shutdown those pesky executor services chuck norris style
    } catch(final IOException ioException) {
      System.err.println("Couldn't open output file");
      System.exit(1);
    }
  }

  private static void benchmark() {
    List<Runner> allRunners = new LinkedList<>();
    allRunners.add(DART_RUNNER);
    allRunners.addAll(DART2JS_RUNNERS);
    allRunners.addAll(JS_RUNNERS);
    for(List<? extends Runner> r : DEFRAC_RUNNERS.values()) {
      allRunners.addAll(r);
    }

    for(Runner runner : allRunners) {
      maxRunnerNameLength =
          Math.max(runner.name.length(), maxRunnerNameLength);
    }

    for(String benchmark : BENCHMARKS) {
      println("Running "+benchmark+" ...");

      // dart
      measure(benchmark, DART_RUNNER);

      // dart2js
      try {
        final String dartSrc = TON80_BENCHMARKS + "/lib/src/" + benchmark + "/dart/" + benchmark + ".dart";
        run(PATH_TO_DART2JS, "-o", dartSrc + ".js", dartSrc);

        for(final Runner runner : DART2JS_RUNNERS) {
          measure(benchmark, runner);
        }
      } catch(IOException ioException) {
        for(final Runner runner : DART2JS_RUNNERS) {
          fail(benchmark, runner);
        }
        printStackTrace(ioException);
      } catch(InterruptedException interrupt) {
        Thread.currentThread().interrupt();
        return;
      }

      //js
      for(final Runner runner : JS_RUNNERS) {
        measure(benchmark, runner);
      }

      // defrac
      for(String platform : DEFRAC_PLATFORMS) {
        try {
          configureAndCompileDefracApp(platform, benchmark);
        } catch(IOException ioException) {
          for(Runner runner : DEFRAC_RUNNERS.get(platform)) {
            fail(benchmark, runner);
          }
          printStackTrace(ioException);
          continue;
        } catch(InterruptedException interrupt) {
          Thread.currentThread().interrupt();
          return;
        }

        for(Runner runner : DEFRAC_RUNNERS.get(platform)) {
          measure(benchmark, runner);
        }
      }

      if(IS_LINUX) {
        plot(benchmark);
      }
    }
  }

  static void plot(String benchmark) {
    try {
      final List<Result> results = new LinkedList<>(RESULTS.get(benchmark));

      Collections.sort(results, new Comparator<Result>() {
        @Override
        public int compare(Result a, Result b) {
          return a.run.compareTo(b.run);
        }
      });

      final Path datFile = Files.createTempFile(benchmark, ".dat");
      final Path svgFile = Paths.get(benchmark+".svg");
      final Path csvFile = Paths.get(benchmark+".csv");

      Files.deleteIfExists(svgFile);
      Files.deleteIfExists(csvFile);

      try(PrintWriter datPrinter = new PrintWriter(Files.newBufferedWriter(datFile, StandardCharsets.UTF_8));
          PrintWriter csvPrinter = new PrintWriter(Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8))) {
        datPrinter.println("# Platform\tMean (runs/sec)\tError (±%)\tBest (runs/sec)");
        csvPrinter.println("Platform;Mean (runs/sec); Error (±%);Best (runs/sec)");
        for(final Result result : results) {
          if(Double.isNaN(result.mean)) {
            datPrinter.println(result.run + "\t0\t0\t0");
            csvPrinter.println(result.run + ";0;0;0");
          } else {
            datPrinter.println(result.run + "\t" + result.mean + "\t" + result.error + "\t" + result.best);
            csvPrinter.println(result.run + ";" + result.mean + ";" + result.error + ";" + result.best);
          }
        }
      }

      List<String> plot = Arrays.asList(
          "set output \""+svgFile.toAbsolutePath().toString()+"\"",
          "set title \""+benchmark+'"',
          "set terminal svg size 640,480 fname 'Verdana' fsize 10",
          "set ylabel \"Score (runs/sec)\"",
          "set grid ytics lc rgb \"#dddddd\" lw 1 lt 0",
          "set grid xtics lc rgb \"#dddddd\" lw 1 lt 0",
          "set key noenhanced",
          "set tic scale 0",
          "set xtics nomirror rotate by -45 font \",8\"",
          "set style data histograms",
          "set style histogram",
          "set style fill solid 1.0 border 0",
          "set boxwidth 0.9",
          "plot \""+datFile.toAbsolutePath().toString()+"\" using 2:xtic(1) notitle linecolor rgb \"#15C7E0\""
      );

      StringBuilder commands = new StringBuilder();
      for(String command : plot) {
        commands.append(command).append(';');
      }

      run("gnuplot", "-e", commands.toString());
      Files.delete(datFile);
    } catch(Exception e) {
      printStackTrace(e);
    }
  }

  static void fail(String benchmark, Runner runner) {
    RESULTS.get(benchmark).add(new Result(runner.name, Double.NaN, Double.NaN, Double.NaN));
    println("  - " + padRight(runner.name) + " : <compile failed>");
  }

  static void printStackTrace(Exception e) {
    if (PRINT_ERRORS) {
      e.printStackTrace(System.err);
    }

    e.printStackTrace(stderr);
  }

  static void measure(String benchmark, Runner runner) {
    double[] scores = extractScores(benchmark, runner);
    Result result = Result.create(runner.name, scores);

    List<Result> results = RESULTS.get(benchmark);
    if(null == results) {
      results = new LinkedList<>();
      RESULTS.put(benchmark, results);
    }
    results.add(result);
    println("  - "+padRight(runner.name)+" : "+result);
  }

  static double[] extractScores(String benchmark, Runner runner) {
    return extractScores(benchmark, runner, 10);
  }

  static double[] extractScores(String benchmark, Runner runner, int iterations) {
    double[] scores = new double[iterations];

    for(int i = 0; i < iterations; ++i) {
      scores[i] = 1.0e6 / extractScore(benchmark, runner);
    }

    return scores;
  }

  static double extractScore(String benchmark, Runner runner) {
    try {
      String stdout = runner.stdoutOf(benchmark);
      Matcher matcher = PATTERN.matcher(stdout);

      if(matcher.find()) {
        return Double.parseDouble(matcher.group(1));
      }
      return Double.NaN;
    } catch(IOException | NumberFormatException exception) {
      if(PRINT_ERRORS) {
        exception.printStackTrace(System.err);
      }
      return Double.NaN;
    } catch(InterruptedException interrupt) {
      Thread.currentThread().interrupt();
      return Double.NaN;
    }
  }

  static double computeBest(double[] scores) {
    double best = scores[0];
    for(int i = 1; i < scores.length; i++) {
      best = Math.max(best, scores[i]);
    }
    return best;
  }

  static double computeMean(double[] scores) {
    double sum = 0.0;
    for(double score : scores) {
      sum += score;
    }
    return sum / scores.length;
  }

  static double computeStandardDeviation(double[] scores, double mean) {
    double deltaSquaredSum = 0.0;
    for(double score : scores) {
      double delta = score - mean;
      deltaSquaredSum += delta * delta;
    }
    double variance = deltaSquaredSum / (scores.length - 1);
    return Math.sqrt(variance);
  }

  static double computeTDistribution(int n) {
    if (n >= 474) return 1.96;
    else if (n >= 160) return 1.97;
    else if (n >= TABLE.length) return 1.98;
    else return TABLE[n];
  }

  static String padRight(String str) {
    char[] pad = new char[maxRunnerNameLength - str.length()];
    Arrays.fill(pad, ' ');
    //noinspection StringBufferReplaceableByString
    return new StringBuilder(maxRunnerNameLength).append(str).append(pad).toString();
  }

  static void configureAndCompileDefracApp(String platform, String benchmark) throws IOException, InterruptedException {
    defrac(platform+":clean");
    defrac(platform + ":config debug false");
    defrac(platform+":config strictMode false");
    defrac(platform+":config main defrac.benchmark."+benchmark);
    defrac(platform+":compile");
  }

  static String defrac(String args) throws IOException, InterruptedException {
    return run(Arrays.asList(PATH_TO_DEFRAC, "-p", DEFRAC_BENCHMARKS, args));
  }

  static String run(String ...command) throws IOException, InterruptedException {
    return run(Arrays.asList(command));
  }

  static String run(List<String> command) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command);
    Process p = builder.start();
    ProcessOutputConsumer stdoutConsumer = new ProcessOutputConsumer(p.getInputStream());
    ProcessOutputConsumer stderrConsumer = new ProcessOutputConsumer(p.getErrorStream());
    EXECUTOR_SERVICE.submit(stdoutConsumer);
    EXECUTOR_SERVICE.submit(stderrConsumer);

    int exitCode = p.waitFor();

    if(0 != exitCode) {
      throw new IOException("Exit code ("+exitCode+")\n\n"+stdoutConsumer.output()+"\n\n"+stderrConsumer.output());
    }

    String stderr = stderrConsumer.output();
    if(!stderr.isEmpty()) {
      throw new IOException(stderr);
    }

    return stdoutConsumer.output();
  }

  static class ProcessOutputConsumer implements Runnable {
    InputStream in;
    StringBuilder builder = new StringBuilder();

    ProcessOutputConsumer(InputStream in) {
      this.in = in;
    }

    @Override
    public void run() {
      try(BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
        String line;
        boolean isFirst = true;
        while((line = reader.readLine()) != null) {
          if(!isFirst) {
            builder.append('\n');
          }
          isFirst = false;
          builder.append(line);
        }
      } catch(IOException ioException) {
        printStackTrace(ioException);
      }
    }

    synchronized String output() {
      return builder.toString();
    }
  }

  static abstract class Runner {
    String name;

    Runner(String name) {
      this.name = name;
    }

    abstract String stdoutOf(String benchmark) throws IOException, InterruptedException ;
  }

  static class DefracRunners {
    static class JVM extends Runner {
      JVM() {
        super("defrac:jvm");
      }

      @Override
      String stdoutOf(String benchmark) throws IOException, InterruptedException {
        return run(PATH_TO_JAVA, "-cp", DEFRAC_BENCHMARKS + "/target/jvm",  "defrac.benchmark." + benchmark);
      }
    }

    static class D8 extends Runner {
      D8() {
        super("defrac:v8");
      }

      @Override
      String stdoutOf(String benchmark) throws IOException, InterruptedException {
        return run(PATH_TO_D8, "-f", DEFRAC_BENCHMARKS + "/target/web/defrac.benchmark/app.js");
      }
    }

    static class SpiderMonkey extends Runner {
      SpiderMonkey() {
        super("defrac:sM");
      }

      @Override
      String stdoutOf(String benchmark) throws IOException, InterruptedException {
        return run(PATH_TO_JS, "-f", DEFRAC_BENCHMARKS + "/target/web/defrac.benchmark/app.js");
      }
    }

    static class Linux extends Runner {
      Linux() {
        super("defrac:c++");
      }

      @Override
      String stdoutOf(String benchmark) throws IOException, InterruptedException {
        return run(DEFRAC_BENCHMARKS + "/target/linux/app");
      }
    }
  }

  static class DartRunner extends Runner {
    DartRunner() {
      super("dart");
    }

    @Override
    String stdoutOf(String benchmark) throws IOException, InterruptedException {
      return run(PATH_TO_DART, TON80_BENCHMARKS+"/lib/src/"+benchmark+"/dart/"+benchmark+".dart");
    }
  }

  static class Dart2JSRunners {
    static class D8 extends Runner {
      D8() {
        super("dart2js:v8");
      }

      @Override
      String stdoutOf(String benchmark) throws IOException, InterruptedException {
        return run(PATH_TO_D8, "-f", TON80_BENCHMARKS+"/lib/src/"+benchmark+"/dart/"+benchmark+".dart.js");
      }
    }

    static class SpiderMonkey extends Runner {
      SpiderMonkey() {
        super("dart2js:sM");
      }

      @Override
      String stdoutOf(String benchmark) throws IOException, InterruptedException {
        return run(PATH_TO_JS, "-f", TON80_BENCHMARKS+"/lib/src/"+benchmark+"/dart/"+benchmark+".dart.js");
      }
    }
  }

  static class JSRunners {
    static class D8 extends Runner {
      D8() {
        super("js:v8");
      }

      @Override
      String stdoutOf(String benchmark) throws IOException, InterruptedException {
        return run(PATH_TO_D8,
            "-f", TON80_BENCHMARKS+"/lib/src/common/javascript/bench.js",
            "-f", TON80_BENCHMARKS+"/lib/src/"+benchmark+"/javascript/"+benchmark+".js");
      }
    }

    static class SpiderMonkey extends Runner {
      SpiderMonkey() {
        super("js:sM");
      }

      @Override
      String stdoutOf(String benchmark) throws IOException, InterruptedException {
        return run(PATH_TO_JS,
            "-f", TON80_BENCHMARKS+"/lib/src/common/javascript/bench.js",
            "-f", TON80_BENCHMARKS+"/lib/src/"+benchmark+"/javascript/"+benchmark+".js");
      }
    }
  }

  static Pattern PATTERN = Pattern.compile("((\\d)+(\\.(\\d)+)?) us");

  static double[] TABLE = {
      Double.NaN, Double.NaN, 12.71,
      4.30, 3.18, 2.78, 2.57, 2.45, 2.36, 2.31, 2.26, 2.23, 2.20, 2.18, 2.16,
      2.14, 2.13, 2.12, 2.11, 2.10, 2.09, 2.09, 2.08, 2.07, 2.07, 2.06, 2.06,
      2.06, 2.05, 2.05, 2.05, 2.04, 2.04, 2.04, 2.03, 2.03, 2.03, 2.03, 2.03,
      2.02, 2.02, 2.02, 2.02, 2.02, 2.02, 2.02, 2.01, 2.01, 2.01, 2.01, 2.01,
      2.01, 2.01, 2.01, 2.01, 2.00, 2.00, 2.00, 2.00, 2.00, 2.00, 2.00, 2.00,
      2.00, 2.00, 2.00, 2.00, 2.00, 2.00, 2.00, 1.99, 1.99, 1.99, 1.99, 1.99,
      1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99,
      1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99, 1.99 };

  static ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();

  static boolean PRINT_ERRORS = false;

  static class Result {
    String run;
    double best;
    double mean;
    double error;

    @Override
    public String toString() {
      String score = String.format("%7.2f", best);
      String error = String.format("%3.1f", this.error);
      String mean = String.format("%6.2f", this.mean);
      return score+" runs/sec ("+mean+"±"+error+"%)";
    }

    Result(String run, double best, double mean, double error) {
      this.run = run;
      this.best = best;
      this.mean = mean;
      this.error = error;
    }

    static Result create(String run, double[] scores) {
      double mean = computeMean(scores);
      double best = computeBest(scores);

      if(scores.length == 1) {
        return new Result(run, best, mean, 0.0);
      } else {
        int n = scores.length;
        double standardDeviation = computeStandardDeviation(scores, mean);
        double standardError = standardDeviation / Math.sqrt(n);
        double percent = (computeTDistribution(n) * standardError / mean) * 100.0;
        return new Result(run, best, mean, percent);
      }
    }
  }

  static void println(final String msg) {
    System.out.println(msg);
    stdout.println(msg);
    stdout.flush();
  }
}
