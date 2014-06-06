//Run command by java process
public class ProcessRunner{
	//build list command for connect to remote host
	List<String> listCmd = null
	def osInfo = null
	ProcessRunner(){
	}
	ProcessRunner(osInfo){
		this.osInfo = osInfo
	}

	// build list command to remote host 
	private buildListCmdRemote(mapCommand){
		if(this.osInfo.os == "win" ){
			//Commands  for remote Windows host
			if(this.osInfo.host != "" && this.osInfo.host != "localhost" ) {
				// if command format is Map : [type:'wmic or remote' ,commandStr:"command"]
				if(mapCommand instanceof Map){
					if(mapCommand.type == "wmic") {
						listCmd.add("wmic")
						listCmd.add("/NODE:" + this.osInfo.host)
						listCmd.add("/user:" + this.osInfo.user)
						listCmd.add("/password:" + this.osInfo.pass)
					} else {
						this.netUse()
						this.listCmd = new ArrayList<String>()		
						//PSExec place in %Wiperdog_Home%/bin
						this.listCmd.add("PsExec.exe")
						this.listCmd.add("\\\\" +this.osInfo.host )
						this.listCmd.add("-u")
						this.listCmd.add(this.osInfo.user)
						this.listCmd.add("-p")
						this.listCmd.add(this.osInfo.pass)
					}
				}
				//If command format is String ,set default remote using PsExec
				if(mapCommand instanceof String){
					this.netUse()
					this.listCmd = new ArrayList<String>()		
					//PSExec place in %Wiperdog_Home%/bin
					this.listCmd.add("PsExec.exe")
					this.listCmd.add("\\\\" +this.osInfo.host )
					this.listCmd.add("-u")
					this.listCmd.add(this.osInfo.user)
					this.listCmd.add("-p")
					this.listCmd.add(this.osInfo.pass)
				}
				
			} else {
				if(mapCommand instanceof Map ) {
					if(mapCommand.type == "wmic") {
						listCmd.add("wmic")
					}
				}
			}
		} else {
			//Commands for remote Linux host
			if(this.osInfo.host != "" && this.osInfo.host != "localhost" ) {
				listCmd.add("/usr/bin/ssh")
				listCmd.add(this.osInfo.host)
			}
		}
		return this.listCmd
	}
	//Access to shared resources of remote host
	private netUse(){
		this.listCmd = new ArrayList<String>()
		this.listCmd.add("net")
		this.listCmd.add("use")
		this.listCmd.add("\\\\"+this.osInfo.host+"\\ipc\$")
		this.listCmd.add("/user:"+this.osInfo.user)
		this.listCmd.add(this.osInfo.pass)
		def process = listCmd.execute()
		this.listCmd = null

	}
	//Run process closure
	def procExecute(mapCommand,boolean isWaitFor){
		def resultData = [:]
		def tmpList = null
		try {
			this.listCmd = new ArrayList<String>()		
			if(this.osInfo != null) {
				if((this.osInfo.host != null) && (this.osInfo.host != "") || (this.osInfo.host != "localhost")){
					this.listCmd = buildListCmdRemote(mapCommand)
				}
				if(mapCommand instanceof Map){					
					tmpList = (mapCommand.commandStr.trim().split(" ") as List)
				}
				if(mapCommand instanceof String){					
					tmpList = (mapCommand.split(" ") as List)
				}
				
			} else {
				if(mapCommand instanceof Map){
					if(mapCommand.type =="wmic"){
						listCmd.add("wmic")
					}
					tmpList = (mapCommand.commandStr.trim().split(" ") as List)
				}
				if(mapCommand instanceof String){
					tmpList = (mapCommand.split(" ") as List)
				}
			}			
			listCmd.addAll(tmpList)
			ProcessBuilder builder = new ProcessBuilder(listCmd)
			Process proc = builder.start()
			StreamGobbler errorGobbler = new StreamGobbler(proc.getErrorStream(), resultData,'err');
			StreamGobbler outputGobbler = new StreamGobbler(proc.getInputStream(), resultData,'out');
			errorGobbler.start();
			outputGobbler.start();
			//Read output and error from process executing
			proc.getOutputStream().close()
			if(isWaitFor){
				resultData['exitVal'] = proc.waitFor()
			}
			errorGobbler.join()
			outputGobbler.join()
		} catch (Exception ex){
			ex.printStackTrace();
		}
		return resultData
	}
}

