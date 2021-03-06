import org.quartz.Job
import org.quartz.InterruptableJob
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder
import groovy.json.*
import org.codehaus.jackson.*


class CustomJob implements Serializable, Job{
	static final long serialVersionUID = 1L;
	String jobName;
	Closure fetchAction;
	String query;
	def parsedData = [:]
	def accumulate
	def groupKeys
	def strFinally
	def jobCommand
	def jobFormat
	DefaultSender sender = new DefaultSender()
	List<Sender> senderList = new ArrayList<Sender>()
	
	def isJobFinishedSuccessfully = false
	def binding 
	
	
	public void execute(JobExecutionContext arg0) throws JobExecutionException {
		// Thread.currentThread().contextClassLoader = CustomJob.class.getClassLoader()

		try{
			String jobName = arg0.getJobDetail().getKey().getName()
			Helper helper = new Helper()			
			parsedData = helper.parseJobFile(new File("../var/job/${jobName}.job"))			
			JobDataMap dataMap = arg0.getJobDetail().getJobDataMap()
			// JobDataMap dataMap = arg0.getMergedJobDataMap()
			this.jobName = parsedData["JOB"]
			this.fetchAction = parsedData["FETCHACTION"]
			this.query = parsedData["QUERY"]
			this.accumulate = parsedData["ACCUMULATE"]
			this.groupKeys = parsedData["GROUPKEY"]
			this.strFinally = parsedData["FINALLY"]
			this.jobCommand=parsedData[ResourceConstants.DEF_COMMAND ]
			this.jobFormat=parsedData[ResourceConstants.DEF_FORMAT]
			this.strFinally = parsedData[ResourceConstants.DEF_FINALLY]
			
			binding = helper.binding
			def now = (new Date()).getTime().intdiv(1000)
			def lastExe = binding.getVariable("lastexecution")
			def interval = 1
			if(lastExe != null && lastExe != [:]){
				interval = now - lastExe
			}
			binding.setVariable("interval", interval)
			
			def result = execute(dataMap, binding)			
			binding.setVariable("lastexecution", now)
			
			dataMap.result = [:]
			dataMap.result.data = result
			
			// Process for PERSISTENTDATA, PrevOutput, lastExecution
			def PERSISTENTDATA_File = new File("../tmp/monitorjobdata/PersistentData/" + jobName + ".txt")
			def PERSISTENTDATA = binding.getVariable("PERSISTENTDATA")
			writeData(PERSISTENTDATA, PERSISTENTDATA_File)
			def prevOUTPUT_File = new File("../tmp/monitorjobdata/PrevOUTPUT/" + jobName + ".txt")
			def prevOUTPUT = binding.getVariable("prevOUTPUT")
			writeData(prevOUTPUT, prevOUTPUT_File)
			def lastExecution_File = new File("../tmp/monitorjobdata/LastExecution/" + jobName + ".txt")
			def lastExecution = binding.getVariable("lastexecution")
			writeData(lastExecution, lastExecution_File)
			
			// Generate metadata for job's result
			dataMap.result = generateMetaData(dataMap)
			
			// Process send data
			def mapDest = parsedData[ResourceConstants.DEF_DEST]
			if (senderList.isEmpty()) {
				sender.mergeSender(mapDest, senderList);
			}
			processSendData(senderList, dataMap.result)
			if(dataMap['sql'] != null){
				dataMap['sql'].close()
			}
		}catch(ex){
			ex.printStackTrace()
		}
	}
	
	/**
	 * Get data after processing and Set data of current Job
	 * @param data Data to write into file 
	 * @param dataMap DataMap to store data on memory
	 * @param dataFile File's path
	 * @param jobName Job's name
	 */
	void writeData(data, dataFile){
		def jsonoutput = new JsonOutput()
		def outputFile
		// datatype is Long when data is lastexecution
		// write to file all the time
		// return to prevent exception at getSize(). (Long doesn't have getSize method)
		if(data instanceof Long || data instanceof Integer){
			outputFile = jsonoutput.toJson(data)
			dataFile.setText(outputFile)
			return;
		}
		// process when data is prevOUTPUT or PERSISTENTDATA
		// Set Data of current job
		outputFile = jsonoutput.toJson(data)
		dataFile.setText(outputFile)
	}
	
	/**
	 * Process to send data
	 * @param destination : Dest given by job
	 * @param resultData : Data for sending
	 * @return
	 */
	def processSendData(destination, resultData){
		if (destination != null) {
			for (concreteSender in destination) {
				println "-Sender of job:" + concreteSender
				concreteSender.send(resultData)
			}
		}
	}
	
	def generateMetaData(dataMap){
		def result = dataMap.result
		result.sourceJob = jobName
		result.sid = ""
		result.resoureId = parsedData.resourceId
		result.KEYEXPR = parsedData.KEYEXPR
		result.hostId = ""
		result.fetchedAt_bin = ""
		result.istIid = ""
		result.type = parsedData.type
		result.version = "1.0"
		result.fetchAt = (new Date()).format("yyyy-MM-dd HH:mm:ssZ").toString()
		return result
	}
	
	def execute(dataMap, binding){
		def result
		if(fetchAction != null){
			result = fetchAction.call()
		}
		if(query != null){
			result = binding['sql'].rows(query)
		}
		if(accumulate != null){
			result = runAccumulate(binding, result, accumulate, groupKeys)
		}
		if(jobCommand != null){
			def osInfo = null
			if(binding.hasVariable(ResourceConstants.OSINFO)){
				osInfo = binding[ResourceConstants.OSINFO]
			}
			result = runCommand(osInfo,jobCommand, jobFormat)
		}
		if(strFinally != null ) {
			strFinally.call(result)
		}
		return result
	}
	def runCommand(osInfo,commandline, mapFormat) {
		def recordarray = []
		try{
			def procData = null			
			try {
				def proc
				if(binding.hasVariable("procRunner")){				
					proc = binding["procRunner"]
				}else{
					println "***** JobRunner: cannot get Process Runner from binding, program exit abnormally"
					return null
				}					
				procData = proc.procExecute(commandline,true)					
			} catch (Exception ex) {
				isJobFinishedSuccessfully = false
				return null
			}
			if (mapFormat != null) {
				def rxMatch = mapFormat['match']
				def rxSplit = mapFormat['split']
				if(procData.out != null) {
					procData.out.split("\n").each { it ->
						// make one record
						if (rxMatch != null) {
							def s = new Scanner(it)
							s.findInLine(rxMatch)
							def newrecord = [:]
							def colindex = 1
							def result = s.match()
							def grpCount = result.groupCount()
							for (i in 1..grpCount) {
								def keyname = mapFormat[colindex]
								if (keyname != null) {
									newrecord[keyname] = result.group(i)
								}
								++colindex
							}
							s.close();
							recordarray.push(newrecord)
						// make one record
						} else if(rxSplit != null) {
							def arySplitResults = it.split(rxSplit)
							def colindex = 1
							def	recordData = [:]
							mapFormat.each {
								// Get key for recordData
								def keyname = mapFormat[colindex]
								if(colindex > 0) {
									if(keyname != null) {
										// Set value for recordData
										recordData[keyname] = arySplitResults[colindex - 1]
									}
								}
								++colindex
							}
							recordarray.push(recordData)
						} else {
							recordarray.push([it])
						}
					}
				}
			} else {
				return procData
			}
		} catch (Exception ex) {
			String stackTrace = org.apache.commons.lang.exception.ExceptionUtils.getFullStackTrace(ex)
			println ex
			isJobFinishedSuccessfully = false
			return null
		}
		return recordarray
	}
	/**
	 * Run Accumulate closure
	 * @param binding Binding
	 * @param resultData Result data from job
	 * @param strAccumulate Accumulate closure
	 * @param groupKeys Group keys for mapping records
	 * @return resultData Result after running Accumulate
	 */
	def runAccumulate(binding, resultData, strAccumulate, groupKeys){
		def OUTPUT = [:]
		// Set variable to Accumulate command and execute ACCUMULATE
		if((strAccumulate != null) && (resultData != null)){
			// After run QUERRY/COMMAND/FFETCHACTION, set resultData to OUTPUT
			OUTPUT = resultData
			binding.setVariable('OUTPUT', OUTPUT)
			binding.setVariable('groupKeys', groupKeys)
			MathFuncUltilities.doBindSimpleDiff(binding)

			// Get prevOUTPUT after running FETCHACTION/QUERY/COMMAND
			// Run ACCUMULATE
			// Set new prevOUTPUT into binding after running ACCUMULATE
			def temp = [:]
			def prevOUTPUT = binding.getVariable('prevOUTPUT')
			
			//Catch Exception from ACCUMULATE
			try {
				strAccumulate.call()
				resultData = binding.getVariable('OUTPUT')
			} catch (Exception e) {
			   String stackTrace = org.apache.commons.lang.exception.ExceptionUtils.getFullStackTrace(e)
			   println stackTrace
			   resultData = null
			   throw e
			} catch (AssertionError ae) {
				String error_assertion = ae.getMessage()
				String line_number_error = ae.getStackTrace()[2].getLineNumber()
				println "Line : ${line_number_error} - ${error_assertion}"
			}
			if(groupKeys == null){
				prevOUTPUT = OUTPUT
			} else {
				OUTPUT.each {rec->
					String prevOutputKey = ''
					for(groupkey in groupKeys){
						prevOutputKey += rec[groupkey]
					}
					temp[prevOutputKey] = rec
				}
				prevOUTPUT = temp
			}
			binding.setVariable('prevOUTPUT', prevOUTPUT)
		}
		return resultData
	}

}
