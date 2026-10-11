# Agent Rules

* Use picocli for command line parsing
* Use log4j2 for logging
* Remove unnecassery fully qualified class names from code
* Use guice for dependency injection
* Use jakarta ee standard apis and annotations where possible
* Avoid nesting classes, declare classes in their own files where possible
* When editing markdown files only place changes at the bottom of the file, never modify human written text in markdown files
* Never use find and replace on pkl files in tests
* Always keep test files in test resources, dont keep them in strings and write them during tests
* Never verify structured content using `.contains` on its rendered form, always deserialize the data and assert fields using strong types in java
