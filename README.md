# JConnect Command Line JMX Client

JConnect is a simple and lightweight command line JMX client written in java using [jline3](https://github.com/jline/jline3).

## How to use?

The easiest way to use jconnect is to download the tar.gz packaged version from the [maven central repository](https://oss.sonatype.org/service/local/artifact/maven/redirect?r=comgithubtimmy80-1001&g=com.github.timmy80&a=jconnect&v=LATEST&e=tar.gz&c=bin) or build it yourself.

Untar the archive and get into it:
```
tar -zxvf jconnect-1.0.0-bin.tar.gz
cd jconnect-1.0.0/
```

JConnect uses a properties file that must contain at least the property **jmxport**.  
The default name is jconnect.properties but this name can be changed using an environment variable.

Example: conf/jconnect.properties
```
jmxport=10000
```

Start Jconnect
```
./bin/jconnect
```

## How to build?

JConnect is a maven project, you can build it simply by running the following command:
```
mvn clean package
```

The results will be available in the target directory.

## Inline usage

JConnect has been designed to provide an interactive management interface but also to simplify the creation of management scripts.  
Any command that you can use in the interactive interface can be passed directly in arguments.  

Let's take an example:  
I've a bean STATISTICS that exposes two method `void resetStats()` and `String displayStats(String filter)`.  
Then I can write the following script:
displayStatsAndReset.sh
```
#!/bin/bash

if [ -z "$1" ]; then
  arg='*'
fi
./bin/jconnect STATISTICS displayStats $arg || exit $?
./bin/jconnect STATISTICS resetStats
```

## All the properties

```
jmxport
jmxhost=myserver // hostname of the jvm (default is localhost)
jmxdomain=timmy.app // jmx domain filter (default is *). You can use this to limit the list of beans.
```

## Change the name of the propertie file

If your properties file is not names jconnect.properties then you'll have to set the name is the JCONNECTPROPERTIES environment variable:
```
export JCONNECTPROPERTIES=foo.properties
```
