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

```bash
#!/bin/bash

if [ -z "$1" ]; then
  arg='*'
fi
./bin/jconnect STATISTICS displayStats $arg || exit $?
./bin/jconnect STATISTICS resetStats
```

## Command line options (since 1.3.0)

If you don't want to configure jconnect using a properties file, you can simply give it the options in the command line arguments:

```bash
# Example of command line with options
./bin/jconnect -h myserverip -p 9090 -- STATISTICS displayStats
```

```bash
# Command line options
usage: jconnect
 -d,--domain <arg>   JMX domain. * by default.
 -h,--host <arg>     hostname or ip of the JMX server
 -help,--help        print this message
 -p,--port <arg>     port of the JMX server
 ```

## All the properties

```
jmxport
jmxhost=myserver // hostname of the jvm (default is localhost)
jmxdomain=timmy.app // jmx domain filter (default is *). You can use this to limit the list of beans.
```

## Environment config

Jconnect can also be configured using some environment variables.

```bash
# value=default 
JCONNECTPROPERTIES=jconnect.properties # Properties files that describes host, port, domain... to use
JCONNECTHISTORY=.jconnect.history # path to the history file used to store jconnect command line history
JMXHOST=localhost # hostname or ip of the JMX server
JMXPORT=null      # port of the JMX server
JMXDOMAIN=*       # JMX domain. * by default.

```

## Change the name of the properties file

If your properties file is not named jconnect.properties then you'll have to set the name is the JCONNECTPROPERTIES environment variable:

```bash
export JCONNECTPROPERTIES=foo.properties
```
