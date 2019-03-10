# JConnect Command Line JMX Client

JConnect is a simple and lightweight command line JMX client written in java using [jline3](https://github.com/jline/jline3).

## How to use?

JConnect uses a properties file that must contain at least the property **jmxport**.  
The default name is jconnect.properties but you can give the name you want to this file.

Example: jconnect.properties
```
jmxport=10000
```

Set the classpath with the properties file into it and start:
```
export CLASSPATH=./:jconnect-1.0.0-aio.jar
java timmy.toolbox.cmdline.JConnect 
```

Remark: the above classpath assumes that your properties files is located in `./`. For example if you choose to put it in a "conf" directory, then change `./` by `./conf/`.

## Inline usage

JConnect has been designed to provide an interactive management interface but also to simplify the creation of management scripts.  
Any command that you can use in the interactive interface can be passed directly in arguments.  

Let's take an example:  
I've a bean STATISTICS that exposes two method `void resetStats()` and `String displayStats(String filter)`.  
Then I can write the following script:
displayStatsAndReset.sh
```
#!/bin/bash
export CLASSPATH=./:jconnect-0.0.1-SNAPSHOT-aio.jar

if [ -z "$1" ]; then
  arg='*'
fi
java timmy.toolbox.cmdline.JConnect STATISTICS displayStats $arg
java timmy.toolbox.cmdline.JConnect STATISTICS resetStats
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
