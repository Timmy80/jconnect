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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
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

import org.apache.commons.lang3.StringUtils;
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
import org.jline.terminal.TerminalBuilder;

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
	String jmxhost = null;
	String jmxport = null;
	String jmxDomain = null;
	
	/** status code of the program */
	int exitCode = 0;

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
	
	public JConnect() {
		try
		{
			String wPropertiesFile = System.getenv("JCONNECTPROPERTIES");
			if(wPropertiesFile != null)
				propertiesFile = wPropertiesFile;
			
			InputStream input = JConnect.class.getClassLoader().getResourceAsStream(propertiesFile);
			if(input==null){
		        throw new FileNotFoundException("Unable to find " + propertiesFile+" from classpath.");
			}
			props.load(input);
			jmxhost=props.getProperty("jmxhost","localhost");
			jmxport=props.getProperty("jmxport");
			if(jmxport == null)
				throw new IOException("property jmxport not found!");
			jmxDomain=props.getProperty("jmxdomain","*");
			
			JMX_URL = String.format("service:jmx:rmi:///jndi/rmi://%s:%s/jmxrmi", jmxhost, jmxport);
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
	
	public MBeanInfo getBean(String name) throws InstanceNotFoundException, IntrospectionException, ReflectionException, IOException, MalformedObjectNameException {
		ObjectName objName = getBeans().get(name);
		if(objName == null)
			return null;
		else
			return mbsc.getMBeanInfo(objName);
	}
	
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

	
	public void stop() {
		stop(0);
	}
	
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

			if("exit".equals(cmd[0]))
				System.exit(0);

			if("help".equals(cmd[0])) {
				System.out.println("type '?' to get the list of the tasks");
				System.out.println("Use tab to autocomplete your commands");
				System.out.println("Set an attribute: <Task Id> set <attribute> <value>");
				System.out.println("Get an attribute: <Task Id> get <attribute>");
				System.out.println("Call a method:    <Task Id> <method> [arguments...]");
				return;
			}

			if(cmd.length < 2) {
				logger.warn("Error missing arguments: {}", Arrays.toString(cmd));
				System.err.println("Error missing arguments");
				exitCode=3;
				return;
			}

			ObjectName name = getBeans().get(cmd[0]);

			if("set".equals(cmd[1])) {
				if(cmd.length < 4) {
					logger.warn("Error missing arguments for set operation: {}", Arrays.toString(cmd));
					System.err.println("Error missing arguments for set operation");
					exitCode=3;
					return;
				}

				Attribute attr = new Attribute(cmd[2], cmd[3]);
				try
				{
					mbsc.setAttribute(name, attr);
					System.out.println(cmd[2]+" has been set to "+cmd[3]);
					logger.info("Successfull call to {}", Arrays.toString(cmd));
				}
				catch (InvalidAttributeValueException e) {
					logger.warn("Invalid value for "+cmd[2]+". ", e);
					System.err.println(System.lineSeparator()+"Invalid value for "+cmd[2]+". "+e.getMessage());
				} catch (AttributeNotFoundException e) {
					logger.warn("Invalid attribute "+cmd[2]+" for "+cmd[0]+". ", e);
					System.err.println(System.lineSeparator()+"Invalid attribute "+cmd[2]+" for "+cmd[0]+". "+e.getMessage());
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
				}		
			}
			else if("?".equals(cmd[1])) {
				// display methods signatures
				
				MBeanInfo infos = mbsc.getMBeanInfo(name);
				MBeanOperationInfo[] ops = infos.getOperations();
				for(MBeanOperationInfo operation : ops) {
					System.out.println(displaySignature(operation));
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
						switch (param.getType()) {
						case "int":
						case "java.lang.Integer":
							params[i] = Integer.parseInt(cmd[i+2]);
							break;
						case "double":
						case "java.lang.Double":
							params[i] = Double.parseDouble(cmd[i+2]);
							break;
	
						case "float":
						case "java.lang.Float":
							params[i] = Float.parseFloat(cmd[i+2]);
							break;
	
						case "boolean":
						case "java.lang.Boolean":
							params[i] = Boolean.parseBoolean(cmd[i+2]);
							break;
	
						case "java.lang.String":
							params[i] = cmd[i+2];
							break;
	
						case "long":
						case "java.lang.Long":
							params[i] = Long.parseLong(cmd[i+2]);
							break;
	
						case "byte":
						case "java.lang.Byte":
							params[i] = Byte.parseByte(cmd[i+2]);
							break;
	
						case "short":
						case "java.lang.Short":
							params[i] = Short.parseShort(cmd[i+2]);
							break;
	
						case "char":
						case "java.lang.Character":
							params[i] = new Character(cmd[i+2].charAt(0));
							break;
						default:
							logger.warn("Cannot call a method using the non primitive data type "+param.getType()+". {}", Arrays.toString(cmd));
							System.err.println("Cannot call a method using the non primitive data type "+param.getType());
							exitCode=4;
							isValid = false;
							break;
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
		}
	}

	int execute(String[] args)  {
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

		try
		{
			if(args.length > 0) {
				
				executeCommand(args);
				
			}
			else {
			
				LineReaderImpl consoleReader = (LineReaderImpl) LineReaderBuilder.builder().terminal(TerminalBuilder.terminal()).build();
				consoleReader.setCompleter(new ConsoleCompletor(this));
	
				System.out.println("Welcome to JConnect console. Type help to get started.");
				
				String line;
				while ((line = consoleReader.readLine(COMMAND_PROMPT)) != null) {
					
					String[] cmd = line.split(" ");
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

	}

	public static void main(String[] args) {
		System.exit(new JConnect().execute(args));		
	}

}
