/**
 * MIT License
 * 
 * Copyright (c) 2019 Anthony Thomas
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package timmy.toolbox.cmdline;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;

import javax.management.Attribute;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.TerminalBuilder;

/**
 * JConnect: A very simple Command Line JMX Client using jline3 for the command line autocompletion. * 
 * @author Anthony THOMAS
 * 
 *
 */
public class JConnect implements NotificationListener {

	private static final String COMMAND_PROMPT = "$> ";

	private static String JMX_URL;
	private JMXConnector jmxc = null;
	private MBeanServerConnection mbsc = null;
	Timer timer = null;
	Logger logger = LogManager.getLogger("jconnect");
	
	// properties
	Properties props = new Properties();
	String propertiesFile = "jconnect.properties";
	String historyFile = null;
	String jmxhost = "localhost";
	String jmxport = null;
	String jmxDomain = "*";
	
	/** status code of the program */
	int exitCode = 0;

	/**
	 * The jline3 Completer implementation for JConnect.<br>
	 * This does the completion of the commands when users press tab.
	 * 
	 */
	public static class ConsoleCompletor implements Completer {

		final JConnect jconnect;
		final Logger logger;
		final String jmxDomain;

		public ConsoleCompletor(JConnect jconnect) {
			this.jconnect = jconnect;
			this.logger = jconnect.logger;
			this.jmxDomain=jconnect.jmxDomain;
		}

		@Override
		public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
			try
			{
				String buffer = line.line();
				if (StringUtils.isEmpty(buffer) || buffer.indexOf(' ') == -1) {
					completeCommandName(buffer, candidates);
				}
				else {
					completeOperationsForBean(line, candidates);
				}
			}
			catch(IOException e) {
				logger.error("IOException on completion.", e);
				System.err.println(System.lineSeparator()+"Disconnected from "+JMX_URL+"! "+System.lineSeparator()+e.toString());
				System.exit(1);
			}
		}

		private void completeOperationsForBean(ParsedLine line, List<Candidate> candidates) throws IOException {
			try {
				MBeanInfo infos = jconnect.getBean(line.words().get(0));
				if(infos == null)
					return;

				if(line.words().size() == 3 && (line.words().get(1).equals("set") || line.words().get(1).equals("get"))) {
					for(MBeanAttributeInfo attr : infos.getAttributes()) {
						Candidate candidate = new Candidate(attr.getName());
						candidates.add(candidate);
					}
				}
				else if(line.words().size() <= 2) {
					for(MBeanOperationInfo operation : infos.getOperations()) {
						Candidate candidate = new Candidate(operation.getName());
						candidates.add(candidate);
					}

					candidates.add(new Candidate("set"));
					candidates.add(new Candidate("get"));
					candidates.add(new Candidate("operations"));
					candidates.add(new Candidate("attributes"));
				}

			} catch (MalformedObjectNameException | InstanceNotFoundException | IntrospectionException | ReflectionException e) {
				logger.error("Exception on completeOperationsForBean.", e);
			}
		}

		private void completeCommandName(String buf, List<Candidate> candidates) throws IOException {

			try {
				for(String name : jconnect.getBeans().keySet()) {
					Candidate candidate = new Candidate(name);
					candidates.add(candidate);
				}
			}
			catch (MalformedObjectNameException e) {
				logger.error("Exception on completeCommandName.", e);
			}
		}

	}
	
	/**
	 * JConnect's constructor load the properties file.<br>
	 * This may exit the program if the configuration cannot be loaded.
	 * @param args the options passed in the command line arguments
	 */
	public JConnect(String[] args) {
		try
		{
			// load from environment variables
			jmxhost   = getEnv("JMXHOST",   jmxhost);
			jmxport   = getEnv("JMXPORT",   jmxport);
			jmxDomain = getEnv("JMXDOMAIN", jmxDomain);
			
			historyFile = getEnv("JCONNECTHISTORY", historyFile);
			
			// load from properties file (if available)
			propertiesFile = getEnv("JCONNECTPROPERTIES", propertiesFile);
			InputStream input = JConnect.class.getClassLoader().getResourceAsStream(propertiesFile);
			if(input!=null){
				props.load(input);
				jmxhost   = props.getProperty("jmxhost",   jmxhost);
				jmxport   = props.getProperty("jmxport",   jmxport);
				jmxDomain = props.getProperty("jmxdomain", jmxDomain);
				historyFile = props.getProperty("jconnect.history", historyFile);
			}
			
			// load from command line arguments
			Options options = new Options();
			options.addOption("help", "help",   false, "print this message");
			options.addOption("h",    "host",   true,  "hostname or ip of the JMX server");
			options.addOption("p",    "port",   true,  "port of the JMX server");
			options.addOption("d",    "domain", true,  "JMX domain. * by default.");
			
			CommandLineParser parser = new DefaultParser();
			CommandLine cmd = parser.parse( options, args);
			
			if (cmd.hasOption("help")) {
				// automatically generate the help statement
				HelpFormatter formatter = new HelpFormatter();
				formatter.printHelp( "jconnect", options, true );
				System.exit(0);
			}
			
			if (cmd.hasOption('h')) {
				jmxhost = cmd.getOptionValue('h');
			}
			
			if (cmd.hasOption('p')) {
				jmxport = cmd.getOptionValue('p');
			}
			
			if (cmd.hasOption('d')) {
				jmxDomain = cmd.getOptionValue('d');
			}
			
			// check
			if(jmxport == null)
				throw new IOException("property jmxport not found!");
			
			// build JMX URL
			JMX_URL = String.format("service:jmx:rmi:///jndi/rmi://%s:%s/jmxrmi", jmxhost, jmxport);
		}
		catch(org.apache.commons.cli.ParseException e){
			System.err.println(e.getMessage());
			System.exit(2);
		}
		catch(IOException e) {
			logger.fatal("IOException on properties loading.", e);
			System.err.println(e.getMessage());
			System.exit(2);
		}
	}

	@Override
	public void handleNotification(Notification notification, Object handback) {
		logger.warn(notification);
		System.err.println(System.lineSeparator()+"Disconnected from "+JMX_URL+"!");
		stop(1);
	}
	
	/**
	 * Get all the available beans.
	 * @return a Map(Name, ObjectName) of the all the available beans. Never null but may be empty.
	 * @throws MalformedObjectNameException in case of a JMX issue
	 * @throws IOException in case of a JMX issue
	 */
	public Map<String, ObjectName> getBeans() throws MalformedObjectNameException, IOException{
		HashMap<String, ObjectName> beans = new HashMap<>();
		
		for (ObjectName name : new TreeSet<ObjectName>(mbsc.queryNames(new ObjectName(jmxDomain+":*"), null))) {
			String wName = name.getKeyProperty("type");
			if(wName == null)
				wName = StringUtils.substringAfterLast(name.toString(), "=");
			if(wName.isEmpty())
				wName=name.toString();
				
			beans.put(wName, name);
		}
		
		return beans;
	}
	
	/**
	 * Get MBeanInfo of a bean.
	 * @param name the name of the been we are looking for
	 * @return null if the bean is not found.
	 * @throws InstanceNotFoundException in case of a JMX issue
	 * @throws IntrospectionException in case of a JMX issue
	 * @throws ReflectionException in case of a JMX issue
	 * @throws IOException in case of a JMX issue
	 * @throws MalformedObjectNameException in case of a JMX issue
	 */
	public MBeanInfo getBean(String name) throws InstanceNotFoundException, IntrospectionException, ReflectionException, IOException, MalformedObjectNameException {
		ObjectName objName = getBeans().get(name);
		if(objName == null)
			return null;
		else
			return mbsc.getMBeanInfo(objName);
	}
	
	/**
	 * Get the signature of a JMX exposed method to display it into console.
	 * @param operation the method to display
	 * @return the string representation of the method
	 */
	public String displaySignature(MBeanOperationInfo operation) {
		StringBuilder builder = new StringBuilder();
		builder.append(StringUtils.removeStart(operation.getReturnType(), "java.lang.")).append(" ");
		builder.append(operation.getName()).append("(");
		for(int i = 0; i < operation.getSignature().length; i++) {
			if(i>0)
				builder.append(", ");
			builder.append(StringUtils.removeStart(operation.getSignature()[i].getType(), "java.lang.")).append(" ").append(operation.getSignature()[i].getName());
		}
		builder.append(")");
		return builder.toString();
	}
	
	public Object convert(String value, String type) {
		Object wResult=null;
		switch (type) {
			case "int":
			case "java.lang.Integer":
				wResult = Integer.parseInt(value);
				break;
			case "double":
			case "java.lang.Double":
				wResult = Double.parseDouble(value);
				break;
	
			case "float":
			case "java.lang.Float":
				wResult = Float.parseFloat(value);
				break;
	
			case "boolean":
			case "java.lang.Boolean":
				wResult = Boolean.parseBoolean(value);
				break;
	
			case "java.lang.String":
				wResult = value;
				break;
	
			case "long":
			case "java.lang.Long":
				wResult = Long.parseLong(value);
				break;
	
			case "byte":
			case "java.lang.Byte":
				wResult = Byte.parseByte(value);
				break;
	
			case "short":
			case "java.lang.Short":
				wResult = Short.parseShort(value);
				break;
	
			case "char":
			case "java.lang.Character":
				wResult = new Character(value.charAt(0));
				break;
			default:
				throw new IllegalArgumentException("Invalid non primitive data type "+type);
		}
		
		return wResult;
	}

	/**
	 * stop JConnect with the status code 0
	 */
	public void stop() {
		stop(0);
	}
	
	/**
	 * stop JConnect with the specified exit code
	 * @param code the exit code
	 */
	public void stop(int code) {
		try {
			timer.cancel();
			jmxc.close();
		} catch (IOException e1) {
			logger.error("unexpected error on stop. ",e1);
		}
		finally {
			System.exit(code);
		}
	}
	
	void executeCommand(String[] cmd) throws IOException {
		try
		{
			exitCode=0; // reset code for each command
			
			if("?".equals(cmd[0])) {
				for(String name : getBeans().keySet())
					System.out.println(name);
				return;
			}

			if("exit".equals(cmd[0]) || "quit".equals(cmd[0]))
				System.exit(0);

			if("help".equals(cmd[0])) {
				System.out.println("type '?' to get the list of the beans");
				System.out.println("Use tab to autocomplete your commands");
				System.out.println("Set an attribute: <Bean Id> set <attribute> <value>");
				System.out.println("Get an attribute: <Bean Id> get <attribute>");
				System.out.println("Call a method:    <Bean Id> <method> [arguments...]");
				return;
			}

			if(cmd.length < 2) {
				logger.warn("Error missing arguments: {}", Arrays.toString(cmd));
				System.err.println("Error missing arguments");
				exitCode=3;
				return;
			}

			ObjectName name = getBeans().get(cmd[0]);
			if(name == null) {
				System.err.println("Invalid Bean "+cmd[0]+"!");
				logger.warn("Invalid Bean {}!", cmd[0]);
				exitCode=4;
				return;
			}

			if("set".equals(cmd[1])) {
				if(cmd.length < 4) {
					logger.warn("Error missing arguments for set operation: {}", Arrays.toString(cmd));
					System.err.println("Error missing arguments for set operation");
					exitCode=3;
					return;
				}

				try {
					// find the attribute
					Object wActualValue = mbsc.getAttribute(name, cmd[2]);
					
					// prepare the new value of the attribute + convert the value of the command line to the type of the actual value
					Attribute attr = new Attribute(cmd[2], convert(cmd[3], wActualValue.getClass().getName()));
					mbsc.setAttribute(name, attr);
					System.out.println(cmd[2]+" has been set to "+cmd[3]);
					logger.info("Successfull call to {}", Arrays.toString(cmd));
				}
				catch (InvalidAttributeValueException e) {
					logger.warn("Invalid value for "+cmd[2]+". ", e);
					System.err.println(System.lineSeparator()+"Invalid value for "+cmd[2]+". "+e.getMessage());
					exitCode=4;
				} catch (AttributeNotFoundException e) {
					logger.warn("Invalid attribute "+cmd[2]+" for "+cmd[0]+". ", e);
					System.err.println(System.lineSeparator()+"Invalid attribute "+cmd[2]+" for "+cmd[0]+". "+e.getMessage());
					exitCode=4;
				}
				catch (IllegalArgumentException e) {
					logger.warn("Cannot set attribute "+cmd[2]+": "+e.getMessage()+". {}", Arrays.toString(cmd));
					System.err.println("Cannot set attribute "+cmd[2]+": "+e.getMessage());
					exitCode=4;
				}
			}
			else if("get".equals(cmd[1])) {
				if(cmd.length < 3) {
					logger.warn("Error missing arguments for get operation: {}", Arrays.toString(cmd));
					System.err.println("Error missing arguments for get operation");
					exitCode=3;
					return;
				}

				try {
					System.out.println(mbsc.getAttribute(name, cmd[2]));
					logger.info("Successfull call to {}", Arrays.toString(cmd));
				} catch (AttributeNotFoundException e) {
					logger.warn("Invalid attribute "+cmd[2]+" for "+cmd[0]+". ", e);
					System.err.println(System.lineSeparator()+"Invalid attribute "+cmd[2]+" for "+cmd[0]+". "+e.getMessage());
					exitCode=4;
				}		
			}
			else if("?".equals(cmd[1]) || "operations".equals(cmd[1])) {
				// display methods signatures
				
				MBeanInfo infos = mbsc.getMBeanInfo(name);
				MBeanOperationInfo[] ops = infos.getOperations();
				for(MBeanOperationInfo operation : ops) {
					System.out.println(displaySignature(operation));
				}
			}
			else if("attributes".equals(cmd[1])) {
				MBeanInfo infos = mbsc.getMBeanInfo(name);
				MBeanAttributeInfo[] attrs = infos.getAttributes();

				try {
					if(attrs != null) {
						for(MBeanAttributeInfo attrInfo : attrs) {
							System.out.println(attrInfo.getName()+"=M"+mbsc.getAttribute(name, attrInfo.getName()));
						}
					}
					logger.info("Successfull call to {}", Arrays.toString(cmd));
				} catch (AttributeNotFoundException e) {
					logger.warn("Exception on getAttribute.", e);
					System.err.println(System.lineSeparator()+"Exception on getAttribute. "+e.getMessage());
					exitCode=4;
				}
			}
			else {
				MBeanInfo infos = mbsc.getMBeanInfo(name);
				MBeanOperationInfo op = null;
				MBeanOperationInfo[] ops = infos.getOperations();

				String wFirstArg=(cmd.length >=3)?cmd[2]:null;
				if("?".equals(wFirstArg)) {
					for(MBeanOperationInfo operation : ops) {
						if(operation.getName().equals(cmd[1])){
							System.out.println(displaySignature(operation));
						}
					}
				}
				else {
					// lets find the operation
					int i = 0;
					while(op == null && i < ops.length) {
						// match the operation name and the number of arguments
						if(ops[i].getName().equals(cmd[1]) && ops[i].getSignature().length == cmd.length-2)
							op = ops[i];
						i++;
					}
	
					if(op == null) {
						logger.warn("Error, operation "+cmd[1]+" not found ! {}", Arrays.toString(cmd));
						System.err.println("Error, operation "+cmd[1]+" not found !");
						exitCode=4;
						return;
					}
	
					Object[] params = new Object[op.getSignature().length];
					String[] signature = new String[op.getSignature().length];
	
					boolean isValid = true;
					i = 0;
					for(MBeanParameterInfo param : op.getSignature()) {
						
						try {
							params[i] = convert(cmd[i+2], param.getType());
						}
						catch(IllegalArgumentException e) {
							logger.warn("Cannot call a method using the non primitive data type "+param.getType()+". {}", Arrays.toString(cmd));
							System.err.println("Cannot call a method using the non primitive data type "+param.getType());
							exitCode=4;
							isValid = false;
						}
	
						signature[i] = param.getType();
						i++;
					}
	
					if(isValid == false)
						return;
	
					Object result = mbsc.invoke(name, cmd[1], params, signature);
					if(result != null)
						System.out.println(result.toString().replaceAll("<br>", "\n"));
					logger.info("Successfull call to {}", Arrays.toString(cmd));
				}
			}
		
		}
		catch (MalformedObjectNameException | InstanceNotFoundException | MBeanException | ReflectionException | IntrospectionException | RuntimeMBeanException e) {
			logger.error("Unexpected exception.",e);
			System.err.println(System.lineSeparator()+"Unexpected exception:");
			e.printStackTrace();
			exitCode=5;
		}
	}

	/**
	 * The entry point of JConnect.<br>
	 * It opens the JMX connection and then execute the command given by args or start the interactive command line interface.
	 * @param args the arguments of an inline command to be executed. may be empty.
	 * @return the exit code of JConnect if not stopped by the method stop(int code)
	 */
	public int execute(String[] args)  {
		try
		{
			JMXServiceURL url = new JMXServiceURL(JMX_URL);
			jmxc = JMXConnectorFactory.connect(url, null);
			jmxc.addConnectionNotificationListener(this, null, jmxc);
			
			mbsc = jmxc.getMBeanServerConnection();
			
			// add a timer to check if the remote server is alive
			timer = new Timer();
			timer.scheduleAtFixedRate(new TimerTask() {
				  @Override
				  public void run() {
				    try {
						mbsc.getMBeanCount();
					} catch (IOException e) {
					}
				  }
				}, 1000, 1000);
		}
		catch (IOException e) {
			System.err.println("Cannot connect to "+JMX_URL+"! "+System.lineSeparator()+e.toString());
			System.exit(1);
		}

		DefaultHistory whistory = null;
		try
		{
			if(args.length > 0) {
				
				executeCommand(args);
				
			}
			else {
				LineReaderImpl consoleReader = (LineReaderImpl) LineReaderBuilder.builder().terminal(TerminalBuilder.terminal()).build();
				consoleReader.setCompleter(new ConsoleCompletor(this));
				if(historyFile != null) {
					whistory = new DefaultHistory();
					consoleReader.setVariable(LineReader.HISTORY_FILE, historyFile);
					consoleReader.setHistory(whistory);
				}

	
				System.out.println("Welcome to JConnect console. Type help to get started.");
				
				String line;
				while ((line = consoleReader.readLine(COMMAND_PROMPT)) != null) {
					
					String[] cmd = line.split("\\s+");
					executeCommand(cmd);
	
				}
			
			}
			
			return exitCode;

		}
		catch(IOException e) {
			logger.error("Disconnected from {}!",JMX_URL);
			System.err.println(System.lineSeparator()+"Disconnected from "+JMX_URL+"! "+System.lineSeparator()+e.toString());
			return 1;
		}
		catch(EndOfFileException | UserInterruptException e) { // Exception received on ctrl+d and crtl+c
			stop();
			return 0;
		}
		finally {
			try {
				if(whistory != null)
					whistory.save();
			}
			catch (IOException e) {
				logger.error("faile to save history.", e);
			}
		}

	}
	

	public static String getEnv(String name, String def) {
		String value = System.getenv(name);
		if(value == null)
			value = def;
		
		return value;
	}
	
	public static String getEnv(String name) {
		return getEnv(name, null);
	}
	
	public static Pair<String[], String[]> splitOptionsAndCommand(String[] args){
		String[] opts = new String[0];
		String[] cmds = new String[0];
		ArrayList<String> options = new ArrayList<String>();
		ArrayList<String> command = new ArrayList<String>();
		
		if(args != null && args.length > 0 && args[0].startsWith("-")) { // first arg is an option
			// separate the args by "--"
			boolean match = false;
			for(String opt : args) {
				if("--".equals(opt))
					match = true;
				else if(!match)
					options.add(opt);
				else
					command.add(opt);
			}
			opts = options.toArray(opts);
			cmds = command.toArray(cmds);
		}
		else { // there is no options in the args
			cmds = args;
		}
			
		return new ImmutablePair<String[], String[]>(opts, cmds);
	}

	public static void main(String[] args) {
		Pair<String[], String[]> optAndCmd = splitOptionsAndCommand(args);
		System.exit(new JConnect(optAndCmd.getLeft()).execute(optAndCmd.getRight()));
	}

}
