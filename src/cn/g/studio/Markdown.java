package cn.g.studio;
import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import java.util.regex.*;
final class Markdown {
    static CharSequence render(String text){
        SpannableStringBuilder out=new SpannableStringBuilder();boolean code=false;
        String fence=""+(char)96+(char)96+(char)96;
        String[] lines=text.split("\n",-1);
        for(int i=0;i<lines.length;i++){
            String line=lines[i];if(line.trim().startsWith(fence)){code=!code;continue;}int start=out.length();
            if(code){
                out.append(line);out.setSpan(new TypefaceSpan("monospace"),start,out.length(),33);
                out.setSpan(new BackgroundColorSpan(0xff202b35),start,out.length(),33);
            }else{
                boolean heading=line.matches("^#{1,6} .*");
                if(heading)line=line.replaceFirst("^#{1,6} +","");
                else if(line.startsWith("- ")||line.startsWith("* "))line="• "+line.substring(2);
                Matcher m=Pattern.compile("(\\*\\*([^*]+)\\*\\*|\\x60([^\\x60]+)\\x60)").matcher(line);int pos=0;
                while(m.find()){
                    out.append(line.substring(pos,m.start()));int s=out.length();boolean bold=m.group(2)!=null;
                    out.append(bold?m.group(2):m.group(3));
                    out.setSpan(bold?new StyleSpan(Typeface.BOLD):new TypefaceSpan("monospace"),s,out.length(),33);pos=m.end();
                }
                out.append(line.substring(pos));
                if(heading){out.setSpan(new StyleSpan(Typeface.BOLD),start,out.length(),33);out.setSpan(new RelativeSizeSpan(1.12f),start,out.length(),33);}
            }
            if(i<lines.length-1)out.append('\n');
        }return out;
    }
}
