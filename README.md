# openapi-bundler
A utility that merges multiple OpenAPI files into a single file with all external
references resolved to local component references.

## Why this OpenAPI Bundler

OpenAPI is becoming the de-facto REST API specification, and a lot people have adopted it
to write their RESTful API spec.

For small APIs, it is acceptable to have a single OpenAPI definition file, however for a large scale API
project, it is hard to manage multiple API specifications together.

For an organization with too
many APIs, it is very natural to define sharable object definitions in separate files
in order to avoid duplications and foster consistency across an organization. With multiple files inter-connected together to form
one API specification, the directory structure and external references are too complicated
to manage.

Luckily, there are a lot of tools like editors, parsers and bundlers in the market that
support multiple files. These tools can bundle multiple YAML files together to create
a final version in JSON or YAML format and de-reference external dependencies.

As our light-rest-4j framework encourages a contract driven design approach, the specification
should be created before coding starts. Actually, if the spec is ready, you can use
[light-codegen](https://github.com/networknt/light-codegen) to generate the project.

The generator for [light-rest-4j](https://github.com/networknt/light-rest-4j)
generates also the object model (POJO) from the openapi.json where objects are defined in the specification. In
addition, light-rest-4j requires the final version of the openapi.json to be included
into the service code to do runtime schema validation as well as OAuth 2.0 scope
verification at runtime.

This requires that the final version of openapi.json is self-contained and all models
should be defined in the components section instead of being de-referenced inline.

Existing bundlers such as [swagger-cli](https://github.com/BigstickCarpet/swagger-cli)
cannot handle our specification files correctly, therefore the need arises to create a generally re-usable bundler for OpenAPI definitions.

## Features

### Remote Reference

This bundler can resolve all remote references in openapi.yaml which is the main file
to be processed. If the reference is an object, it will resolve its internal references
first and then move it into components in the generated openapi.json. At the same time
the external reference in openapi.yaml will be changed to local reference with #/components/{key}

If the remote reference is not an object, it will be resolved inline in the generated
openapi.json file.

If you have separate reference files, you must place these files into the same folder your openapi.yaml, or relative to that folder. For example, if you have common
folder that contains all the common OpenAPI files, you might need to copy the common
folder into your folder that contains openapi.yaml for your API.

### Local Reference

If the reference is an object in components, it will resolve all the remote references
in definitions.

If the reference is not an object, an error will occur and the process will exit.

## Usage

The bundler assumes that the input file is openapi.yaml and all the remote reference files
are in the right path.

### Use it as Java utility

```
java -jar target/openapi-bundler.jar <arguments....>

Arguments are:
--dir, -d : The input directory where the YAML files can be found. Must be specified
--file, -f : The name of the YAML file to be bundled. Default = openapi.yaml
--outpu, -o : The output format for the bundled file: yaml | json | both. Default = json

Note: use -DdebugOutput to view debug output

# General usage:
java -jar openapi-bundler.jar  -d <myFolder> -f <input file> -o <json|yaml|both>

# To view debug messages during the bundling process, use the utility with the debugOutput flag
java -DdebugOutput -jar target/swagger-bundler.jar  <folder of swagger.yaml>

# Simplified call, with default values, uses openapi.yaml as input and json as output format
java -jar openapi-bundler.jar  -d <myFolder>

```

To view usage help you can use the following command:
```
Command:
java -jar openapi-bundler.jar  -h

Usage: <main class> [options]
  Options:
    --dir, -d
      The input directory where the YAML files can be found. Must be specified
    --file, -f
      The name of the YAML file to be bundled. Default = openapi.yaml
    --output, -o
      The output format for the bundled file: yaml | json | both. Default = json
      Default: json
    --help, -h      
```

### Use it in an IDE

Another way to run the bundler is from an IDE. Just set the folder of the openapi.yaml file as a program argument and you can easily debug into it.

### Use Docker

There is a Docker [image](https://hub.docker.com/r/networknt/openapi-bundler/) that is
published to Docker Hub.

Here is the command line to call it.

```
TBD
```


With above command line, you can easily build a script to call it as part of your DevOps
flow.
