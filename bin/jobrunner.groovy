import org.codehaus.groovy.tools.RootLoader;
import org.apache.felix.framework.util.Util;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.BundleContext;
@Grapes([
@Grab(group='commons-beanutils', module='commons-beanutils', version='1.8.0'),
@Grab(group='commons-collections', module='commons-collections', version='3.2.1'),
@Grab(group='commons-io', module='commons-io', version='2.2'),
@Grab(group='commons-lang', module='commons-lang', version='2.5'),
@Grab(group='commons-net', module='commons-net', version='3.1'),
@Grab(group='commons-codec', module='commons-codec', version='1.4'),
@Grab(group='c3p0', module='c3p0', version='[0.9.1.2,)'),
@Grab(group='org.quartz-scheduler', module='quartz', version='2.2.0'),

@Grab(group='org.mongodb', module='mongo-java-driver', version='2.9.0'),
@Grab(group='mysql', module='mysql-connector-java', version='5.1.20'),
@Grab(group='org.mongodb', module='mongo-java-driver', version='2.9.0'),

@Grab(group='commons-digester', module='commons-digester', version='2.0'),
@Grab(group='org.codehaus.jackson', module='jackson-core-asl', version='1.9.5'),
@Grab(group='org.codehaus.jackson', module='jackson-mapper-asl', version='1.9.5'),
@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.5.2'),
@Grab(group='com.google.code.gson', module='gson', version='2.2.2'),
@Grab(group='com.gmongo', module='gmongo', version='1.0'),
@Grab(group='log4j', module='log4j', version='1.2.17'),
@Grab(group='org.apache.felix', module='org.apache.felix.configadmin', version='1.2.8'),
@Grab(group='org.apache.felix', module='org.osgi.compendium', version='1.4.0'),
])


import org.quartz.Trigger.TriggerState
import static org.quartz.TriggerBuilder.newTrigger
import org.quartz.SimpleScheduleBuilder
import org.quartz.impl.StdSchedulerFactory
import static org.quartz.CronScheduleBuilder.cronSchedule
import org.quartz.SimpleScheduleBuilder
import org.quartz.DateBuilder
import org.quartz.DateBuilder.IntervalUnit

public class JobRunner{
	private static GroovyClassLoader gcl = new GroovyClassLoader();
        static long WAITING_TIME = 2000 // Wait 20 second until the job finished
	public static void main(String[] args) throws Exception {
                def jobFilePath, cronString		
		if(
			(args == null || args.length<=0 || args.length >6)
			|| (args != null && args.length == 2 && args[0] != '-f')
			|| (args != null && args.length == 4 
				&& (args[0] != '-f'  || (args[2] != '-s' && args[2] != '-w')))
			|| (args != null && args.length == 6 
				&& (args[0] != '-f' 
                                    || args[2] !='-s' || args[4] != '-w')) 
		){
			println "***** JOB RUNNER: Invalid argument"			
			println "Use one of the following syntax: "
			println " jobrunner -f <path>"
			println " jobrunner -f <path> -s <crontab>"
			println " jobrunner -f <path> -w <waiting time>"
			println " jobrunner -f <path> -s <crontab> -w <waiting time>"
			println " NOTICE: waiting time is the time to force the program to wait for job to be executed (in milisecond)"
			return
		}
		jobFilePath = args[1]
		if(args.length == 4 && args[2] == '-w'){
		  WAITING_TIME=Long.parseLong(args[3])
		}else if(args.length == 4 && args[2] == '-s'){
		  cronString = args[3]
		}
		if(args.length == 6){
		  WAITING_TIME = Long.parseLong(args[5])
		  cronString = args[3]
		}
		def binding = new Binding()
		def felix_home = getFelixHome()
		binding.setVariable("felix_home", felix_home)
		boolean isSingleRun = false
		def listExecutingJobs = []
		
		URL [] scriptpath123 = [new File(felix_home + "/" + "lib/groovy/libs.target").toURL(), new File(felix_home + "/" + "lib/groovy/libs.common").toURL()]
		RootLoader rootloader = new RootLoader(scriptpath123, gcl)
		
		def shell = new GroovyShell(rootloader,binding)
		
		def sf = new StdSchedulerFactory()
		def sched = sf.getScheduler()
		sched.start()
		def HelperCls = shell.getClassLoader().loadClass('Helper')
		def helper = HelperCls.newInstance(shell, sched)
		def jobFile = new File(jobFilePath)
		def jobDetail
		if(!jobFile.isAbsolute()){
			//jobFile = new File(binding.getVariable('felix_home') + "/" + args[1])
			jobFile = new File(jobFilePath)
		}
		if( ! jobFile.exists() ) {
			//-- Use relative path extends from FELIX_HOME
			jobFile = new File(binding.getVariable('felix_home') + "/" + jobFilePath)
			if(! jobFile.exists() ){
				//-- Specified a job in FELIX_HOME/var/job
				jobFile = new File(binding.getVariable('felix_home') + "/var/job/" + jobFilePath)
			}else{
				println "***** JOB RUNNER: Job file does not exists: " + binding.getVariable('felix_home') + "/" + jobFilePath
			}
		}
		jobDetail = helper.processJob(jobFile)
			sched.addJob(jobDetail, true);
		def trigger
		
		if(jobDetail != null){
		    // Schedule with corontab 
		    if(cronString != null){
			// Case: schedule job with time interval
			if(cronString.indexOf('i') != -1){
				cronString = "0/" + cronString.substring(0, cronString.length() - 2) + " * * * * ?"
				trigger = newTrigger()
				.withIdentity("jobrunner_trigger")
			        .forJob(jobDetail)
				.withSchedule(cronSchedule(cronString))
				.withDescription(cronString)
			        .startNow()
				.build();
			// Schedule job with delay timeout
			}else if(cronString.indexOf('i') == -1 && cronString.trim().indexOf(' ') == -1){
				long startDelay = Long.parseLong(cronString)
				trigger = newTrigger()
				.withIdentity("jobrunner_trigger")
				.forJob(jobDetail)
				.startAt(DateBuilder.futureDate((int)startDelay, IntervalUnit.MILLISECOND))
				.build();
                        // Case: full crontab string
			}else{
				trigger = newTrigger()
				.withIdentity("jobrunner_trigger")
			        .forJob(jobDetail)
				.withSchedule(cronSchedule(cronString))
				.withDescription(cronString)
			        .startNow()
				.build();
			}
		    // Case: argument number < 4 	
		    }else{
			trigger = newTrigger()
				.withIdentity("jobrunner_trigger")
				.forJob(jobDetail)
				.startNow()
				.build();
		    }
		    println "***** JOBRUNNER: Start schedule JOB"    
	            sched.scheduleJob(trigger)

	            Thread.currentThread().sleep(WAITING_TIME);
	        }else{
	             println "***** JOBRUNNER: Job cannot be created: " +jobDetail
	        }		
	        sched.shutdown()		
	}
	public static String getFelixHome(){
		//	get felix home
		//2013-03-06 Luvina update start
		File currentDir = new File(System.getProperty("bin_home"))
		//2013-03-06 Luvina update end
		def felix_home = currentDir.getParent()
		return felix_home
	}
 }
