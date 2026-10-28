# Agent Rules

* Use picocli for command line parsing
* Use log4j2 for logging
* Remove unnecassery fully qualified class names from code
* Use dagger for dependency injection
* Use jakarta ee standard apis and annotations where possible
* Avoid nesting classes, declare classes in their own files where possible
* When editing markdown files only place changes at the bottom of the file, never modify human written text in markdown files
* Never use find and replace on pkl files in tests
* Always keep test files in test resources, dont keep them in strings and write them during tests
* Never verify structured content using `.contains` on its rendered form, always deserialize the data and assert fields using strong types in java
* Always implement features fully, dont leave TODOs or workarounds to make single tests pass. Solutions must be complete and generic
* When interacting with the filesystem in code always catch exceptions rather than doing defensive checks to avoid race conditions with other processess modifying the filesystem. Prefer `try { fileio() } catch (FileNotFound) {}` to `if (exists(file)) { fileio() }`
