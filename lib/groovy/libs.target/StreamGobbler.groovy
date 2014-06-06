import java.io.*
class StreamGobbler extends Thread {
    InputStream is;
    def resultData;
	def type;
	
    StreamGobbler(InputStream is,resultData,type) {
        this.is = is;
        this.resultData = resultData;
		this.type = type;
    }
	
    public void run() {
        try {
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
			StringBuffer str = new StringBuffer()
            String line=null;
            while ( (line = br.readLine()) != null){
				str.append(line + "\n")
			}
			this.is.close()
			isr.close()
			br.close()	
			this.resultData[this.type] = str.toString()
        } catch (IOException ioe){
            ioe.printStackTrace();
			this.is.close()
			isr.close()
			br.close()	
        }
    }
}