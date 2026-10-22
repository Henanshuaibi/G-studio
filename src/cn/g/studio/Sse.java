package cn.g.studio;
import java.io.*;
final class Sse {
    interface Event {boolean data(String value)throws Exception;}
    static void read(Reader reader,Event event)throws Exception{
        BufferedReader r=new BufferedReader(reader);String line;StringBuilder data=new StringBuilder();boolean first=true;
        while((line=r.readLine())!=null){
            if(first&&line.startsWith("\ufeff"))line=line.substring(1);first=false;
            if(line.length()>1048576)throw new IOException("服务器数据行过大");
            if(line.isEmpty()){
                if(data.length()>0){if(!event.data(data.substring(0,data.length()-1)))return;data.setLength(0);}
            }else if(line.startsWith("data:")){
                String value=line.substring(5);if(value.startsWith(" "))value=value.substring(1);
                if(data.length()+value.length()>1048576)throw new IOException("服务器数据块过大");
                data.append(value).append('\n');
            }
        }
        if(data.length()>0)event.data(data.substring(0,data.length()-1));
    }
}
