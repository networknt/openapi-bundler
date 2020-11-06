package com.networknt.openapi;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provide command line features to process an OpenAPI spec.
 *
 * @author ddobrin
 */
public class Bundler {

    private static final Logger LOG = LoggerFactory.getLogger(Bundler.class);

    @Parameter(description = "operation: The operation to be performed. Supported operations: bundle | validate. Must be specified")
    String operation;

    @Parameter(names = {"--dir", "-d"},
               required = true,
               description = "The input directory where the YAML files can be found for bundling | validation. Mandatory parameter.")
    String dir;

    @Parameter(names = {"--file", "-f"},
               description = "The name of the YAML file to be bundled or validated. Default: openapi.yaml")
    String file;

    @Parameter(names = {"--outputFormat", "-o"},
               description = "The output format for the bundled file: YAML | JSON | both. Default: YAML")
    String output = "yaml";

    @Parameter(names = {"--outputFile", "-of"},
               description = "The name of the bundled and validated OpenAPI file. Default: openapi.bundled")
    String outputFile = "openapi.bundled";

    @Parameter(names = {"--outputDir", "-od"},
               description = "The output directory of the bundled and validated file. Default: same as input directory specified in <dir>")
    String outputDir;

    @Parameter(names = "-debug",
               description = "Debug mode")
    private static boolean debug = false;

    @Parameter(names = {"--help", "-h"},
               help = true)
    private boolean help;

    public static void main(String... argv) {

        try {
            //parse the incoming arguments
            // supported operation:
            // - bundle
            // - validate
            Bundler bundler = new Bundler();
            JCommander jCommander = JCommander.newBuilder().addObject(bundler).build();
            jCommander.parse(argv);
            bundler.run(jCommander);
        } catch (ParameterException e) {
            System.out.println("Error while parsing command-line parameters: " + e.getLocalizedMessage());
            e.usage();
        }
    }

    public void run(JCommander jCommander) {
        // check if help must be displayed
        if (help) {
            jCommander.usage();
            return;
        }

        // first mandatory argument is the folder where the YAML files to be bundled are to be found
        // second argument is optional; allows the setting of an input file name; openapi.yaml is the default
        if (dir == null) {
            throw new RuntimeException("Input directory is required");
        }
        String folder = dir;

        // The input parameter is the folder that contains openapi.yaml and
        // this folder will be the base path to calculate remote references.
        // if the second argument is a different file name, it will be used
        // otherwise, default is "openapi.yaml"
        String fileName = file == null ? "openapi.yaml" : file;

        if (operation.equalsIgnoreCase("validate")) {
            // if the operation is validate, validate the file, in YAML or JSON format, then exit the process
            BundlerProcessor.validateSpecification(Paths.get(
                    dir,
                    fileName));
            return;
        }

        // set output directory.
        // if not set, default it to the input <dir>
        if (outputDir == null) {
            outputDir = folder;
        }

        // bundle the file and validate the resulting file
        LOG.info("OpenAPI Bundler: Bundling API definition with file name <{}>, from directory <{}>",
                         fileName,
                         folder);

        try {
            BundlerProcessor processor = new BundlerProcessor(folder, fileName, outputDir, outputFile);
            processor.setOutputJSON(outFormatEquals("json"));
            processor.setOutputYaml(outFormatEquals("yaml"));
            processor.call();
        } catch (Exception e) {
            LOG.error("Exception occurred", e);
        }

        // completed bundling
        output = output.equalsIgnoreCase("both") ? "YAML & JSON" : output.toUpperCase();
        LOG.info("OpenAPI Bundler: Bundling API definition has completed. Output directory <{}>, in file format {}",
                         dir,
                         output);
    }

    private boolean outFormatEquals(String format) {
        String oLow = output.toLowerCase();
        return format.equals(oLow)||"both".equals(oLow);
    }

}

