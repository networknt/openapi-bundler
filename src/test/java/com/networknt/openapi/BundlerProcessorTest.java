package com.networknt.openapi;

import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Test;

public class BundlerProcessorTest {


    @Test
    public void testOpenApiSpec() throws Exception {
        String bundledFile = "openapi.bundled.yaml";
        BundlerProcessor bp = new BundlerProcessor("target/test-classes", "openapi.spec.yaml", "target", bundledFile);
        bp.call();
        Assert.assertTrue(Files.exists(Paths.get("target", bundledFile)));
    }

    @Test
    public void testRecursiveSpec() throws Exception {
        String bundled = "geojsonservice.yml";
        BundlerProcessor bp = new BundlerProcessor(Paths.get("target/test-classes", "geojsonservice.yml"), Paths.get("target"));
        bp.call();
        Assert.assertTrue(Files.exists(Paths.get("target", "geojsonservice.yml")));
    }
}
