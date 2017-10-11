# Filegen Maven Plugin

### Introduction

This plugin finds `.kts` scripts in the source directory and executes them with the project classpath, outputting a 
file containing the string results of the script execution or a build failure if the script could not execute. This
can be used to generate files at build time dynamically - good for generating boilerplate code, for example.


### Artifacts

This repository is not on Maven Central or Bintray yet - it will be added when the project is less experimental.


### Project Status

Everything is currently experimental and subject to change as part of the 0.x.x releases. These versions should not 
be considered production ready, as the API and implementation may change at any time prior to the 1.0.0 release.
