package hudson.plugins.performance;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;

import hudson.util.ListBoxModel;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PerformancePublisher extends Recorder {
  @Extension
  public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
    @Override
    public String getDisplayName() {
      return Messages.Publisher_DisplayName();
    }

    @Override
    public String getHelpFile() {
      return "/plugin/performance/help.html";
    }

    public List<PerformanceReportParserDescriptor> getParserDescriptors() {
      return PerformanceReportParserDescriptor.all();
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }

      /**
       *
       * Populate the comparison type dynamically based on the user selection from
       * the previous time
       *
       * @return the name of the option selected in the previous run
       */
    public ListBoxModel doFillComparisonTypeItems() {
      ListBoxModel items = new ListBoxModel();
      HashMap<String, String> listOpt= new HashMap<String, String>();


      //getting the user selected value
      String temp = getOptionType();

      if(temp.equalsIgnoreCase("ART")) {

        items.add("Average Response Time", "ART");
        items.add("Median Response Time", "MRT");
        items.add("Percentile Response Time", "PRT");
      } else if(temp.equalsIgnoreCase("MRT")) {

        items.add("Median Response Time", "MRT");
        items.add("Percentile Response Time", "PRT");
        items.add("Average Response Time", "ART");
      } else if(temp.equalsIgnoreCase("PRT")) {

        items.add("Percentile Response Time", "PRT");
        items.add("Average Response Time", "ART");
        items.add("Median Response Time", "MRT");
      }

      return items;
    }
  }


  private int errorFailedThreshold = 0;

  private int errorUnstableThreshold = 0;
  
  private String errorUnstableResponseTimeThreshold = "";



  private double relativeFailedThresholdPositive = 0;

  private double relativeFailedThresholdNegative = 0;

  private double relativeUnstableThresholdPositive = 0;

  private double relativeUnstableThresholdNegative = 0;

  private int nthBuildNumber = 0;

  private boolean modeRelativeThresholds = false;

  private String configType="ART";

  private boolean modeOfThreshold = false;

  private boolean compareBuildPrevious = false;

  public static final String ART = "ART";

  public static final String MRT = "MRT";

  public static final String PRT = "PRT";

  public static String optionType="ART";



  private boolean modePerformancePerTestCase = false;

  /**
   * @deprecated as of 1.3. for compatibility
   */
  private transient String filename;

  /**
   * Configured report parsers.
   */
  private List<PerformanceReportParser> parsers;



@DataBoundConstructor
  public PerformancePublisher(int errorFailedThreshold,
                            int errorUnstableThreshold,
                            String errorUnstableResponseTimeThreshold,
                            double relativeFailedThresholdPositive,
                            double relativeFailedThresholdNegative,
                            double relativeUnstableThresholdPositive,
                            double relativeUnstableThresholdNegative,
                            int nthBuildNumber,
                            boolean modePerformancePerTestCase,
                            String comparisonType,
                            boolean modeOfThreshold,
                            boolean compareBuildPrevious,
                            List<? extends PerformanceReportParser> parsers) {

    this.errorFailedThreshold = errorFailedThreshold;
    this.errorUnstableThreshold = errorUnstableThreshold;
    this.errorUnstableResponseTimeThreshold = errorUnstableResponseTimeThreshold;

    this.relativeFailedThresholdPositive = relativeFailedThresholdPositive;
    this.relativeFailedThresholdNegative = relativeFailedThresholdNegative;
    this.relativeUnstableThresholdPositive = relativeUnstableThresholdPositive;
    this.relativeUnstableThresholdNegative = relativeUnstableThresholdNegative;

    this.nthBuildNumber = nthBuildNumber;
    this.configType = comparisonType;
    this.optionType = comparisonType;
    this.modeOfThreshold = modeOfThreshold;
    this.compareBuildPrevious = compareBuildPrevious;

    if (parsers == null)
        parsers = Collections.emptyList();
    this.parsers = new ArrayList<PerformanceReportParser>(parsers);
    this.modePerformancePerTestCase = modePerformancePerTestCase;
  }




  public static File getPerformanceReport(AbstractBuild<?, ?> build,
      String parserDisplayName, String performanceReportName) {
    return new File(build.getRootDir(),
        PerformanceReportMap.getPerformanceReportFileRelativePath(
            parserDisplayName,
            getPerformanceReportBuildFileName(performanceReportName)));
  }

  @Override
  public Action getProjectAction(AbstractProject<?, ?> project) {
    return new PerformanceProjectAction(project);
  }

  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.BUILD;
  }

  public List<PerformanceReportParser> getParsers() {
    return parsers;
  }


  /**
   * <p>
   * Delete the date suffix appended to the Performance result files by the
   * Maven Performance plugin
   * </p>
   * 
   * @param performanceReportWorkspaceName
   * @return the name of the PerformanceReport in the Build
   */
  public static String getPerformanceReportBuildFileName(
      String performanceReportWorkspaceName) {
    String result = performanceReportWorkspaceName;
    if (performanceReportWorkspaceName != null) {
      Pattern p = Pattern.compile("-[0-9]*\\.xml");
      Matcher matcher = p.matcher(performanceReportWorkspaceName);
      if (matcher.find()) {
        result = matcher.replaceAll(".xml");
      }
    }
    return result;
  }

  /**
   * look for performance reports based in the configured parameter includes.
   * 'includes' is - an Ant-style pattern - a list of files and folders
   * separated by the characters ;:,
   */
  protected static List<FilePath> locatePerformanceReports(FilePath workspace,
      String includes) throws IOException, InterruptedException {

    // First use ant-style pattern
    /*
      try {
      FilePath[] ret = workspace.list(includes);
      if (ret.length > 0) {
        return Arrays.asList(ret);
      }
    */
    //Agoley : Possible fix, if we specify more than one result file pattern
    try {
      String parts[] = includes.split("\\s*[;:,]+\\s*");
      List<FilePath> files = new ArrayList<FilePath>();
        for (String path : parts) {
          FilePath[] ret = workspace.list(path);
          if (ret.length > 0) {
             files.addAll(Arrays.asList(ret));
          }
      }
     if (!files.isEmpty()) return files;

    } catch (IOException e) {
    }

    //Agoley:  seems like this block doesn't work    
    // If it fails, do a legacy search
    ArrayList<FilePath> files = new ArrayList<FilePath>();
    String parts[] = includes.split("\\s*[;:,]+\\s*");
    for (String path : parts) {
      FilePath src = workspace.child(path);
      if (src.exists()) {
        if (src.isDirectory()) {
          files.addAll(Arrays.asList(src.list("**/*")));
        } else {
          files.add(src);
        }
      }
    }
    return files;
  }

  @Override
  public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
          throws InterruptedException, IOException {

    PrintStream logger = listener.getLogger();
    double thresholdTolerance = 0.00000001;
    Result result = Result.SUCCESS;


    //For absolute error/unstable threshold..
    if (!modeOfThreshold) {
      try {
        List<UriReport> curruriList = null;
        HashMap<String, String> responseTimeThresholdMap = null;

        if (!"".equals(this.errorUnstableResponseTimeThreshold) && this.errorUnstableResponseTimeThreshold != null) {

          responseTimeThresholdMap = new HashMap<String, String>();
          String[] lines = this.errorUnstableResponseTimeThreshold.split("\n");

          for (String line : lines) {
            String[] components = line.split(":");
            if (components.length == 2) {
              logger.println("Setting threshold: " + components[0] +":"+ components[1]);
              responseTimeThresholdMap.put(components[0], components[1]);
            }
          }
        }

        if (errorUnstableThreshold >= 0 && errorUnstableThreshold <= 100) {
            logger.println("Performance: Percentage of errors greater or equal than "
                    + errorUnstableThreshold + "% sets the build as "
                    + Result.UNSTABLE.toString().toLowerCase());
        }
        else {
            logger.println("Performance: No threshold configured for making the test "
                    + Result.UNSTABLE.toString().toLowerCase());
        }
        if (errorFailedThreshold >= 0 && errorFailedThreshold <= 100) {
            logger.println("Performance: Percentage of errors greater or equal than "
                    + errorFailedThreshold + "% sets the build as "
                    + Result.FAILURE.toString().toLowerCase());
        }
        else {
            logger.println("Performance: No threshold configured for making the test "
                    + Result.FAILURE.toString().toLowerCase());
        }

            // add the report to the build object.
        PerformanceBuildAction a = new PerformanceBuildAction(build, logger, parsers);
        build.addAction(a);
        logger.print("\n\n\n");

        for (PerformanceReportParser parser : parsers) {

          String glob = parser.glob;
          logger.println("Performance: Recording " + parser.getReportName() + " reports '" + glob + "'");

          List<FilePath> files = locatePerformanceReports(build.getWorkspace(), glob);

          if (files.isEmpty()) {
            if (build.getResult().isWorseThan(Result.UNSTABLE)) {
              return true;
            }
            build.setResult(Result.FAILURE);
            logger.println("Performance: no " + parser.getReportName()
                    + " files matching '" + glob
                    + "' have been found. Has the report generated?. Setting Build to "
                    + build.getResult());
            return true;
          }

          List<File> localReports = copyReportsToMaster(build, logger, files, parser.getDescriptor().getDisplayName());
          Collection<PerformanceReport> parsedReports = parser.parse(build, localReports, listener);

          // mark the build as unstable or failure depending on the outcome.
          for (PerformanceReport r : parsedReports) {

            r.setBuildAction(a);
            double errorPercent = r.errorPercent();

            curruriList = r.getUriListOrdered();

            if (errorFailedThreshold >= 0 && errorPercent - errorFailedThreshold > thresholdTolerance) {
                result = Result.FAILURE;
                build.setResult(Result.FAILURE);
            } else if (errorUnstableThreshold >= 0 && errorPercent - errorUnstableThreshold > thresholdTolerance) {
                result = Result.UNSTABLE;
            }

            long average = r.getAverage();
            logger.println(r.getReportFileName() + " has an average of: "+ Long.toString(average));

            try {
              if (responseTimeThresholdMap != null && responseTimeThresholdMap.get(r.getReportFileName()) != null) {
                if (Long.parseLong(responseTimeThresholdMap.get(r.getReportFileName())) <= average) {
                    logger.println("UNSTABLE: " + r.getReportFileName() + " has exceeded the threshold of ["+Long.parseLong(responseTimeThresholdMap.get(r.getReportFileName()))+"] with the time of ["+Long.toString(average)+"]");
                    result = Result.UNSTABLE;
                }
              }
            } catch (NumberFormatException nfe) {
                logger.println("ERROR: Threshold set to a non-number [" + responseTimeThresholdMap.get(r.getReportFileName()) + "]");
                result = Result.FAILURE;
                build.setResult(Result.FAILURE);

            }
            if (result.isWorseThan(build.getResult())) {
                build.setResult(result);
            }
            logger.println("Performance: File " + r.getReportFileName()
                    + " reported " + errorPercent
                    + "% of errors [" + result + "]. Build status is: "
                    + build.getResult());

            logger.print("\n\n\n");
          }
        }
      } catch(Exception e) {
      }
    } else {

      // For relative comparisons between builds...
      try {
        if (relativeFailedThresholdNegative <= 100 && relativeFailedThresholdPositive <= 100 ) {
            logger.println("Performance: Percentage of relative difference outside -"
                    + relativeFailedThresholdNegative + " to +" +relativeFailedThresholdPositive+" % sets the build as "
                    + Result.FAILURE.toString().toLowerCase());
        } else {
            logger.println("Performance: No threshold configured for making the test "
                    + Result.FAILURE.toString().toLowerCase());
        }

        if (relativeUnstableThresholdNegative <= 100 && relativeUnstableThresholdPositive <= 100 ) {
            logger.println("Performance: Percentage of relative difference outside -"
                    + relativeUnstableThresholdNegative + " to +" +relativeUnstableThresholdPositive+" % sets the build as "
                    + Result.UNSTABLE.toString().toLowerCase());
        } else {
            logger.println("Performance: No threshold configured for making the test "
                    + Result.UNSTABLE.toString().toLowerCase());
        }

        List<UriReport> curruriList = null;

        // add the report to the build object.
        PerformanceBuildAction a = new PerformanceBuildAction(build, logger, parsers);
        build.addAction(a);
        logger.print("\n\n\n");


        for (PerformanceReportParser parser : parsers) {
          String glob = parser.glob;
          List<FilePath> files = locatePerformanceReports(build.getWorkspace(), glob);

          if (files.isEmpty()) {
            if (build.getResult().isWorseThan(Result.UNSTABLE)) {
                return true;
            }
            build.setResult(Result.FAILURE);
            logger.println("Performance: no " + parser.getReportName()
                    + " files matching '" + glob
                    + "' have been found. Has the report generated?. Setting Build to "
                    + build.getResult());
            return true;
          }

          List<File> localReports = copyReportsToMaster(build, logger, files, parser.getDescriptor().getDisplayName());
          Collection<PerformanceReport> parsedReports = parser.parse(build, localReports, listener);


          for (PerformanceReport r : parsedReports) {
            r.setBuildAction(a);
            double errorPercent = r.errorPercent();

            //uri list is the list of labels in the current jmeter results file
            curruriList = r.getUriListOrdered();
          }
        }

        // getting previous build/nth previous build..
        AbstractBuild prevBuild = null;

        if(compareBuildPrevious){
          prevBuild = getPrevBuild(build, listener);
        } else {
          prevBuild = getnthBuild(build, listener);
        }

        List<UriReport> prevuriList = null;

        if (prevBuild != null) {
          PerformanceBuildAction b = new PerformanceBuildAction(prevBuild, logger, parsers);
          prevBuild.addAction(b);

          //getting files related to the previous build selected
          for (PerformanceReportParser parser : parsers) {
            String glob = parser.glob;
            logger.println("Performance: Recording " + parser.getReportName()+ " reports '" + glob + "'");

            List<File> localReports = getExistingReports(prevBuild, logger, parser.getDescriptor().getDisplayName());
            Collection<PerformanceReport> parsedReports = parser.parse(prevBuild, localReports, listener);


            for (PerformanceReport r : parsedReports) {
              r.setBuildAction(b);

              //uri list is the list of labels in the previous jmeter results file
              prevuriList = r.getUriListOrdered();
            }
          }

          result = Result.SUCCESS;
          String failedLabel = null, unStableLabel = null;
          double relativeDiff=0, relativeDiffPercent=0;

          logger.print("\nComparison build no. - "+prevBuild.number+" and "+build.number +" using ");


          //Comparing both builds based on either average, median or 90 percentile response time...
          if(configType.equalsIgnoreCase("ART")) {

            logger.println("Average response time\n\n");
            logger.println("====================================================================================================================================");
            logger.println("PrevBuildURI\tCurrentBuildURI\t\tPrevBuildURIAvg\t\tCurrentBuildURIAvg\tRelativeDiff\tRelativeDiffPercentage ");
            logger.println("====================================================================================================================================");
          } else if(configType.equalsIgnoreCase("MRT")) {

            logger.println("Median response time\n\n");
            logger.println("====================================================================================================================================");
            logger.println("PrevBuildURI\tCurrentBuildURI\t\tPrevBuildURIMed\t\tCurrentBuildURIMed\tRelativeDiff\tRelativeDiffPercentage ");
            logger.println("====================================================================================================================================");
          } else if(configType.equalsIgnoreCase("PRT")) {

            logger.println("90 Percentile response time\n\n");
            logger.println("====================================================================================================================================");
            logger.println("PrevBuildURI\tCurrentBuildURI\t\tPrevBuildURI90%\t\tCurrentBuildURI90%\tRelativeDiff\tRelativeDiffPercentage ");
            logger.println("====================================================================================================================================");
          }


          //comparing the labels and calculating the differences...
          for (int i = 0; i < prevuriList.size(); i++) {
            for (int j = 0; j < curruriList.size(); j++) {
              if(prevuriList.get(i).getStaplerUri().equalsIgnoreCase(curruriList.get(j).getStaplerUri())) {

                relativeDiff = curruriList.get(j).getAverage() - prevuriList.get(i).getAverage();
                relativeDiffPercent = ((double) relativeDiff * 100) / prevuriList.get(i).getAverage();
                relativeDiffPercent = Math.round(relativeDiffPercent * 100);
                relativeDiffPercent = relativeDiffPercent/100;

                relativeDiff = curruriList.get(j).getMedian() - prevuriList.get(i).getMedian();
                relativeDiffPercent = ((double) relativeDiff * 100) / prevuriList.get(i).getMedian();
                relativeDiffPercent = Math.round(relativeDiffPercent * 100);
                relativeDiffPercent = relativeDiffPercent/100;

                relativeDiff = curruriList.get(j).get90Line() - prevuriList.get(i).get90Line();
                relativeDiffPercent = ((double) relativeDiff * 100) / prevuriList.get(i).get90Line();
                relativeDiffPercent = Math.round(relativeDiffPercent * 100);
                relativeDiffPercent = relativeDiffPercent/100;


                if(configType.equalsIgnoreCase("ART")) {

                  relativeDiff = curruriList.get(j).getAverage() - prevuriList.get(i).getAverage();
                  relativeDiffPercent = ((double) relativeDiff * 100) / prevuriList.get(i).getAverage();

                  relativeDiffPercent = Math.round(relativeDiffPercent * 100);
                  relativeDiffPercent = relativeDiffPercent/100;

                  logger.println(prevuriList.get(i).getStaplerUri() + "\t" + curruriList.get(j).getStaplerUri() + "\t\t" +
                          prevuriList.get(i).getAverage() + "\t\t\t" + curruriList.get(j).getAverage() + "\t\t\t" + relativeDiff + "\t\t" + relativeDiffPercent);


                } else if(configType.equalsIgnoreCase("MRT")) {

                  relativeDiff = curruriList.get(j).getMedian() - prevuriList.get(i).getMedian();
                  relativeDiffPercent = ((double) relativeDiff * 100) / prevuriList.get(i).getMedian();

                  relativeDiffPercent = Math.round(relativeDiffPercent * 100);
                  relativeDiffPercent = relativeDiffPercent/100;

                  logger.println(prevuriList.get(i).getStaplerUri() + "\t" + curruriList.get(j).getStaplerUri() + "\t\t" +
                          prevuriList.get(i).getMedian() + "\t\t\t" + curruriList.get(j).getMedian() + "\t\t\t" + relativeDiff + "\t\t" + relativeDiffPercent);


                } else if(configType.equalsIgnoreCase("PRT")) {

                  relativeDiff = curruriList.get(j).get90Line() - prevuriList.get(i).get90Line();
                  relativeDiffPercent = ((double) relativeDiff * 100) / prevuriList.get(i).get90Line();

                  relativeDiffPercent = Math.round(relativeDiffPercent * 100);
                  relativeDiffPercent = relativeDiffPercent/100;

                  logger.println(prevuriList.get(i).getStaplerUri() + "\t" + curruriList.get(j).getStaplerUri() + "\t\t" +
                          prevuriList.get(i).get90Line() + "\t\t\t" + curruriList.get(j).get90Line() + "\t\t\t" + relativeDiff + "\t\t" + relativeDiffPercent);

                }

                //setting the build status based on the differences calculated...
                if(relativeDiffPercent < 0) {
                  if (relativeFailedThresholdNegative >= 0 && Math.abs(relativeDiffPercent) - relativeFailedThresholdNegative > thresholdTolerance) {

                    result = Result.FAILURE;
                    build.setResult(Result.FAILURE);
                    failedLabel = prevuriList.get(i).getStaplerUri();

                  } else if (relativeUnstableThresholdNegative >= 0 && Math.abs(relativeDiffPercent) - relativeUnstableThresholdNegative > thresholdTolerance) {

                    result = Result.UNSTABLE;
                    unStableLabel = prevuriList.get(i).getStaplerUri();
                  }
                } else if(relativeDiffPercent >= 0) {

                  if (relativeFailedThresholdPositive >= 0 && Math.abs(relativeDiffPercent) - relativeFailedThresholdPositive > thresholdTolerance) {

                    result = Result.FAILURE;
                    build.setResult(Result.FAILURE);
                    failedLabel = prevuriList.get(i).getStaplerUri();

                  } else if (relativeUnstableThresholdPositive >= 0 && Math.abs(relativeDiffPercent) - relativeUnstableThresholdPositive > thresholdTolerance) {

                    result = Result.UNSTABLE;
                    unStableLabel = prevuriList.get(i).getStaplerUri();
                  }
                }

                if (result.isWorseThan(build.getResult())) {
                  build.setResult(result);
                }
              }

            }
          }

          logger.println("------------------------------------------------------------------------------------------------------------------------------------");
          String labelResult = "\nThe label ";
          logger.print((failedLabel != null) ? labelResult + "\"" + failedLabel + "\"" + " caused the build to fail\n" : (unStableLabel != null) ? labelResult + "\"" + unStableLabel + "\"" + " made the build unstable\n" : "");
        }
      } catch (Exception e){
      }
    }
    return true;
  }

  private List<File> copyReportsToMaster(AbstractBuild<?, ?> build,
      PrintStream logger, List<FilePath> files, String parserDisplayName)
      throws IOException, InterruptedException {
    List<File> localReports = new ArrayList<File>();
    for (FilePath src : files) {
      final File localReport = getPerformanceReport(build, parserDisplayName,
          src.getName());
      if (src.isDirectory()) {
        logger.println("Performance: File '" + src.getName()
            + "' is a directory, not a Performance Report");
        continue;
      }
      src.copyTo(new FilePath(localReport));
      localReports.add(localReport);
    }
    return localReports;
  }

  public Object readResolve() {
    // data format migration
    if (parsers == null)
      parsers = new ArrayList<PerformanceReportParser>();
    if (filename != null) {
      parsers.add(new JMeterParser(filename));
      filename = null;
    }
    return this;
  }

  public int getErrorFailedThreshold() {
    return errorFailedThreshold;
  }

  public void setErrorFailedThreshold(int errorFailedThreshold) {
    this.errorFailedThreshold = Math.max(0, Math.min(errorFailedThreshold, 100));
  }

  public int getErrorUnstableThreshold() {
    return errorUnstableThreshold;
  }

  public void setErrorUnstableThreshold(int errorUnstableThreshold) {
    this.errorUnstableThreshold = Math.max(0, Math.min(errorUnstableThreshold,
        100));
  }

  public String getErrorUnstableResponseTimeThreshold(){
	  return this.errorUnstableResponseTimeThreshold;
  }
  
  public void setErrorUnstableResponseTimeThreshold(String errorUnstableResponseTimeThreshold){
	  this.errorUnstableResponseTimeThreshold = errorUnstableResponseTimeThreshold;
  }
  
  public boolean isModePerformancePerTestCase() {
		return modePerformancePerTestCase;
  }

  public void setModePerformancePerTestCase(boolean modePerformancePerTestCase) {
	  this.modePerformancePerTestCase = modePerformancePerTestCase;
  }
  
  public boolean getModePerformancePerTestCase(){
	  return modePerformancePerTestCase;
  }
	  
  public String getFilename() {
    return filename;
  }

  public void setFilename(String filename) {
    this.filename = filename;
  }





  public boolean isART() {
    return configType.compareToIgnoreCase(PerformancePublisher.ART) == 0;
  }

  public boolean isMRT() {
    return configType.compareToIgnoreCase(PerformancePublisher.MRT) == 0;
  }

  public boolean isPRT() {
    return configType.compareToIgnoreCase(PerformancePublisher.PRT) == 0;
  }



  public static File[] getPerformanceReportDirectory(AbstractBuild<?, ?> build,
                                                     String parserDisplayName, PrintStream logger) {
    File folder = new File(build.getRootDir() + "/" + PerformanceReportMap.getPerformanceReportFileRelativePath(parserDisplayName, ""));
    File[] listOfFiles = folder.listFiles();
    return listOfFiles;
  }


    /**
     *       Gets the Build object entered in the text box "Compare with nth Build"
      * @param build, listener
     *
     * @return build object
     * @throws IOException
     */

    // @psingh5 -
  public AbstractBuild getnthBuild(AbstractBuild build, BuildListener listener)
          throws IOException {
    PrintStream logger = listener.getLogger();
    AbstractBuild nthBuild = build;

    int nextBuildNumber = build.number - nthBuildNumber;

    for (int i = 1; i <= nextBuildNumber; i++) {
      nthBuild = (AbstractBuild) nthBuild.getPreviousBuild();
      if (nthBuild == null)
        return null;
    }
    return (nthBuildNumber == 0) ? null : nthBuild;
  }


    /**
     *   Gets the previous build...
     * @param build
     * @param listener
     * @return  build object
     * @throws IOException
     */

  public AbstractBuild getPrevBuild(AbstractBuild build, BuildListener listener)
          throws IOException {
    PrintStream logger = listener.getLogger();
    AbstractBuild nthBuild = build;

    nthBuild = (AbstractBuild) nthBuild.getPreviousBuild();
    if (nthBuild == null) {
      return null;
    } else {
      return nthBuild;
    }

  }


  private List<File> getExistingReports(AbstractBuild<?, ?> build, PrintStream logger, String parserDisplayName)
          throws IOException, InterruptedException {
    List<File> localReports = new ArrayList<File>();
    final File localReport[] = getPerformanceReportDirectory(build, parserDisplayName, logger);

    for (int i = 0; i < localReport.length; i++) {
      localReports.add(localReport[i]);
    }
    return localReports;
  }



  public static String getOptionType() {
    return optionType;
  }


  public double getRelativeFailedThresholdPositive() {
    return relativeFailedThresholdPositive;
  }

  public double getRelativeFailedThresholdNegative() {
    return relativeFailedThresholdNegative;
  }

  public void setRelativeFailedThresholdPositive(double relativeFailedThresholdPositive) {
    this.relativeFailedThresholdPositive = Math.max(0, Math.min(relativeFailedThresholdPositive, 100));
  }

  public void setRelativeFailedThresholdNegative(double relativeFailedThresholdNegative) {
    this.relativeFailedThresholdNegative = Math.max(0, Math.min(relativeFailedThresholdNegative, 100));
  }

  public double getRelativeUnstableThresholdPositive() {
    return relativeUnstableThresholdPositive;
  }

  public double getRelativeUnstableThresholdNegative() {
    return relativeUnstableThresholdNegative;
  }

  public void setRelativeUnstableThresholdPositive(double relativeUnstableThresholdPositive) {
    this.relativeUnstableThresholdPositive = Math.max(0, Math.min(relativeUnstableThresholdPositive,
            100));
  }

  public void setRelativeUnstableThresholdNegative(double relativeUnstableThresholdNegative) {
    this.relativeUnstableThresholdNegative = Math.max(0, Math.min(relativeUnstableThresholdNegative,
            100));
  }

  public int getNthBuildNumber() {
    return nthBuildNumber;
  }

  public void setNthBuildNumber(int nthBuildNumber) {
    this.nthBuildNumber = Math.max(0, Math.min(nthBuildNumber,Integer.MAX_VALUE));
  }

  public String getConfigType() {
    return configType;
  }

  public void setConfigType(String configType) {
    this.configType = configType;
  }

  public boolean getModeOfThreshold() {
    return modeOfThreshold;
  }

  public void setModeOfThreshold(boolean modeOfThreshold) {
    this.modeOfThreshold = modeOfThreshold;
  }

  public boolean getCompareBuildPrevious()  {
    return compareBuildPrevious;
  }

  public void setCompareBuildPrevious(boolean compareBuildPrevious) {
    this.compareBuildPrevious = compareBuildPrevious;
  }

  public void setModeRelativeThresholds(boolean modeRelativeThresholds) {
    this.modeRelativeThresholds = modeRelativeThresholds;
  }

  public boolean getModeRelativeThresholds() {
    return modeRelativeThresholds;
  }


}


