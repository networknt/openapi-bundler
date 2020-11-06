package com.networknt.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;
import com.networknt.oas.OpenApiParser;
import com.networknt.oas.model.OpenApi3;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;


/**
 *  Class resolves all externalized references from within an OpenAPI definition file and bundles the output into a
 *  single OpenAPI file, in JSON or YAML format.
 */
public class BundlerProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(BundlerProcessor.class);
    private static final String RESOLVING_FLAG = "BUNDLER_MAP_FLAG";
    private static final String EXT_REF = "BUNDLER_EXT_REF";
    private static final String JSON_EXT = "json";
    private static final Pattern FILE_EXTS = Pattern.compile("(.*)\\.(json|ya?ml)");
    private static final String DISCRIMINATOR = "discriminator";
    private static final String ONE_OF = "oneOf";
    private static final String REF = "$ref";

    private static final ObjectMapper mapper = new ObjectMapper();

    Map<String, Map<String, Object>> references = new HashMap<>();

    // we have to handle components as a separate map, otherwise, we will have
    // concurrent modification exception while iterating the map and updating components.
    final Map<String, Object> definitions = new HashMap<>();

    // stack to maintain relative location state
    private final Deque<Path> workingDirs = new LinkedList<>();

    private final String folder;
    private final String fileName;
    private final String outputDir;
    private String outputFile;

    private boolean outputYaml = true;
    private boolean outputJSON = false;

    private String yamlExt = "yaml";

    public BundlerProcessor(String folder, String fileName, String outputDir, String outputFile) {

        this.folder = folder;
        this.fileName = fileName;
        this.outputDir = outputDir;
        this.outputFile = outputFile;
    }

    public BundlerProcessor(Path inputSpec, Path outputPath) {

        this.folder = inputSpec.getParent().normalize().toString();
        this.fileName = inputSpec.getFileName().toString();
        this.outputDir = outputPath.normalize().toString();
        this.outputFile = folder.equals(outputDir)?"bundled_"+fileName:fileName;
    }

    /**
     * Main processing method. It is expected that this method be called only once. Repeated invocations result in
     * unspecified behavior.
     *
     * @throws IOException if errors occur finding, reading, or writing files/folders
     */
    public void call() throws IOException {

        Path spec = Paths.get(folder, fileName).normalize();
        workingDirs.addFirst(spec.getParent());

        Map<String, Object> map = loadYaml(spec);

        Map<String, Object> componentsMap = castIt(map.computeIfAbsent("components",
                s -> new HashMap<String, Object>()));
        Map<String, Object> schemasMap = castIt(componentsMap.computeIfAbsent("schemas",
                s -> new HashMap<String, Object>()));

        definitions.putAll(schemasMap);

        // now let's handle the references.
        resolveMap(map);

        // add the resolved components to the main map, before persisting
        schemasMap.putAll(definitions);

        Matcher m = FILE_EXTS.matcher(outputFile);
        if (m.matches()) {
            outputFile = m.group(1);
            String ext = m.group(2);
            if (!JSON_EXT.equals(ext)) {
                yamlExt = ext;
            }
        }
        writeJson(map);
        writeYaml(map);
    }

    private void writeJson(Map<String, Object> map) throws IOException {
        // Convert the map back to JSON and serialize it.
        if (!outputJSON) {
            return;
        }

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);

        writeData(JSON_EXT, json);
    }

    /**
     * Convert the map back to YAML and serialize it.
     */
    private void writeYaml(Map<String, Object> map) throws IOException {

        if (!outputYaml) {
            return;
        }

        YAMLFactory yamlFactory = new YAMLFactory();
        yamlFactory.enable(Feature.MINIMIZE_QUOTES);
        yamlFactory.disable(Feature.SPLIT_LINES);
        yamlFactory.disable(Feature.WRITE_DOC_START_MARKER);
        yamlFactory.disable(Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS);
        yamlFactory.disable(Feature.LITERAL_BLOCK_STYLE);

        ObjectMapper objMapper = new ObjectMapper(yamlFactory);
        String yamlOutput = objMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
        writeData(yamlExt, yamlOutput);
    }

    private void writeData(String suffix, String output) throws IOException {

        LOG.debug("OpenAPI Bundler: write bundled {} file to: {} in directory: {}", suffix, outputFile, outputDir);

        Path dir = Paths.get(outputDir);

        Path outPath = dir.resolve(String.format("%s.%s", outputFile, suffix));
        // write the output

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        Files.write(outPath, output.getBytes());

        // validate the output file
        validateSpecification(outPath);
    }

    private <T> T loadYaml(Path fName) throws IOException {

        try (InputStream is = Files.newInputStream(fName)) {
            return new Yaml().load(is);
        }
    }

    private Map<String, Object> handlerPointer(String pointer) {

        try {
            Map<String, Object> result = new HashMap<>();
            int hashIndex = pointer.indexOf("#");
            if (hashIndex == 0) {
                // There are two cases with local reference. 1, original in
                // local reference and it has path of "definitions" or 2, local reference
                // that extracted from reference file with reference to an object directly.
                String refKey = pointer.substring(pointer.lastIndexOf("/") + 1);

                LOG.debug("refKey = {}", refKey);

                if (pointer.contains("components")) {
                    // if the $ref is an object, keep it that way and if $ref is not an object, make
                    // it inline
                    // and remove it from definitions.
                    Map<String, Object> refMap =castIt(definitions.get(refKey));
                    if (refMap == null) {
                        LOG.error("Could not find reference in definitions for key {}", refKey);
                        throw new BundlerException("Could not find reference in definitions for key " + refKey);
                    }
                    if (isRefMapObject(refMap)) {
                        result.put(REF, pointer);
                    } else {
                        result = refMap;
                    }
                } else {
                    // This is something extracted from extenal file and the reference is still
                    // local.
                    // need to look up for all reference files in order to find it.
                    Map<String, Object> refMap = null;
                    for (Map<String, Object> r : references.values()) {
                        refMap = castIt(r.get(refKey));
                        if (refMap != null) {
                            break;
                        }
                    }

                    if (refMap == null) {
                        throw new BundlerException("Could not resolve reference locally in components for key " + refKey
                                                          + ". Please check your components section.");
                    }
                    if (isRefMapObject(refMap)) {
                        definitions.put(refKey, refMap);
                        result.put(REF, "#/components/schemas/" + refKey);
                    } else {
                        result = refMap;
                    }
                }
            } else {
                // just a link to an external file (ex: ../models/my_model.v1.yml)
                boolean isExtRef = hashIndex > 0;
                Map<String, Object> refMap = isExtRef ? loadExtRef(pointer, hashIndex) : loadRef(pointer);
                // now need to resolve the internal references in refMap.
                if (refMap == null) {
                    throw new BundlerException("Could not find reference in external file for pointer " + pointer);
                }

                // check if the refMap type is object or not.
                if (isRefMapObject(refMap)) {
                    // add to definitions
                    String refKey = refMap.get(isExtRef ? EXT_REF : "title").toString();
                    if (refKey == null) {
                        throw new BundlerException("Unable to determine refKey for pointer: " + pointer);
                    }

                    definitions.put(refKey, refMap);
                    // update the ref pointer to local
                    result.put(REF, "#/components/schemas/" + refKey);
                } else {
                    // simple type, inline refMap instead.
                    result = refMap;
                }
                refMap.remove(EXT_REF);
                resolveMap(refMap);
                workingDirs.removeFirst();
            }
            return result;
        } catch (Exception ex) {
            throw new BundlerException(
                    "Unexpected Exception in OpenAPI Bundler",
                    ex);
        }
    }

    /**
     * Check if the input map is an json object or not.
     *
     * @param refMap input map
     */
    private static boolean isRefMapObject(Map<String, Object> refMap) {

        return "object".equals(refMap.get("type"))||refMap.get(ONE_OF)!=null||isAllOfRefMapObject(refMap.get("allOf"));
    }

    /**
     * Checks whether an allOf list contains a json object.
     *
     * @param allOf entry from spec object
     * @return true if allOf list contains a json object
     */
    private static boolean isAllOfRefMapObject(Object allOf) {
        if (allOf==null) {
            return false;
        }
        List<Object> allList = castIt(allOf);
        boolean allOfRefMap = allList.stream().anyMatch(o -> {
            if (!(o instanceof Map)) {
                return false;
            }
            Map<String, Object> m = castIt(o);
            return isRefMapObject(m);
        });
        return allOfRefMap;
    }

    /**
     * Load external ref
     */
    private Map<String, Object> loadExtRef(String path, int offset) throws IOException {
        // external reference and it must be a relative url
        Map<String, Object> refs = loadRef(path.substring(0, offset));
        String refKey = path.substring(offset + 2);
        LOG.debug("refKey = {}", refKey);
        Map<String, Object> refMap = castIt(refs.get(refKey));
        refMap.put(EXT_REF, refKey);
        return refMap;
    }

    /**
     * load and cache remote reference. folder is a static variable assigned by argv[0] it will check the cache first
     * and only load it if it doesn't exist in cache.
     *
     * @param path the path of remote file
     * @return map of remote references
     */
    private Map<String, Object> loadRef(String path) throws IOException {

        Path p = workingDirs.peekFirst().resolve(path).normalize();
        workingDirs.addFirst(p.getParent());
        Map<String, Object> result = references.get(p.toString());
        if (result == null) {
            LOG.debug("Current path to load = {}", p);
            LOG.debug("Current working dir  = {}", workingDirs.peekFirst());

            result = loadYaml(p);
            references.put(p.toString(), result);

        }
        return result;
    }

    /**
     * It deep iterate a map object and looking for "$ref" and handle it.
     *
     * @param map the map of openapi.yaml
     */
    public void resolveMap(Map<String, Object> map) {

        if (map.containsKey(RESOLVING_FLAG)) {
            // already resolving, avoid stack overflow
            return;
        }
        map.put(RESOLVING_FLAG, RESOLVING_FLAG);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            LOG.debug("resolveMap key = {} value = {}", key, value);
            // mappings can be named refs
            if (DISCRIMINATOR.equals(key)&&map.containsKey(ONE_OF)) {
                entry.setValue(checkDiscriminatorMapping(value));
            } else {
                entry.setValue(checkVal(value));
            }
        }
        map.remove(RESOLVING_FLAG);
    }

    private Object checkVal(Object value) {

        Object rVal = value;
        if (value instanceof Map) {
            // check if this map is $ref, it should be size = 1
            Map<String, Object> valMap = castIt(value);
            if (valMap.size() == 1) {
                Object refString = valMap.get(REF);
                if (refString != null) {
                    LOG.debug("pointer = {}", refString);
                    rVal = handlerPointer(refString.toString());
                }
            }
            resolveMap(valMap);
        } else if (value instanceof List) {
            resolveList(castIt(value));
        }
        return rVal;
    }

    private Object checkDiscriminatorMapping(Object value) {
        if (!(value instanceof Map)) {
            return checkVal(value);
        }
        Map<String, Object> discriminator = castIt(value);
        Map<String,Object> mapping = castIt(discriminator.get("mapping"));
        for (Map.Entry<String, Object> e: mapping.entrySet()) {
            e.setValue(handlerPointer(e.getValue().toString()).get(REF));
        }
        return value;
    }

    public void resolveList(List<Object> list) {

        for (int i = 0; i < list.size(); i++) {
            list.set(i, checkVal(list.get(i)));
        }
    }

    public void setOutputYaml(boolean outputYaml) {

        this.outputYaml = outputYaml;
    }

    public void setOutputJSON(boolean outputJSON) {

        this.outputJSON = outputJSON;
    }

    /**
     * Casts an object to the specified type
     * @param val object to cast
     * @param <T> generic
     * @return properly typed object
     */
    @SuppressWarnings("unchecked")
    static <T> T castIt(Object val) {
        return (T) val;
    }

    public static boolean validateSpecification(Path p) {

        try {
            @SuppressWarnings("unused") OpenApi3 model = (OpenApi3) new OpenApiParser().parse(new File(p.toString()), true);

            LOG.info("OpenAPI3 Validation: Definition file: <{}> is valid ....", p);
            return true;
        } catch (Exception e) {
            LOG.error("OpenAPI3 Validation: Definition file <{}> in directory <{}> failed with exception", p, e);
            return false;
        }
    }

}
