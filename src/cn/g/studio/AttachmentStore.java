package cn.g.studio;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Xml;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

final class AttachmentStore {
    static final int MAX_COUNT=6,MAX_FILE=8*1048576,MAX_IMAGE_SOURCE=20*1048576,MAX_IMAGE=2*1048576,MAX_TEXT_BYTES=1048576,MAX_TEXT=120000;
    static final long MAX_TURN=12L*1048576,MAX_CONTEXT=12L*1048576;
    final File root;
    AttachmentStore(File root)throws IOException{this.root=root;if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建附件目录");}
    File file(String id)throws IOException{
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IOException("附件编号不正确");
        return new File(root,id);
    }
    static String safeName(String name){
        if(name==null||name.trim().isEmpty())return "附件";
        name=name.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]"," ").trim();
        return name.isEmpty()?"附件":name.substring(0,Math.min(name.length(),160));
    }
    Models.Attachment importUri(ContentResolver resolver,Uri uri)throws Exception{
        String name="附件",mime=resolver.getType(uri);
        try(Cursor c=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()){int idx=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(idx>=0)name=c.getString(idx);}
        }
        try(InputStream in=resolver.openInputStream(uri)){
            if(in==null)throw new IOException("无法打开所选文件");return importFile(name,mime,in);
        }
    }
    Models.Attachment importFile(String name,String mime,InputStream in)throws Exception{
        Models.Attachment a=new Models.Attachment();a.name=safeName(name);a.mime=mime==null?"":mime.toLowerCase(Locale.ROOT);
        File target=file(a.id);boolean ok=false;
        try{
            try(OutputStream out=new FileOutputStream(target)){
                byte[] b=new byte[16384];int n;long count=0;
                while((n=in.read(b))!=-1){count+=n;if(count>MAX_IMAGE_SOURCE)throw new IOException("单个文件不能超过 20 MB");out.write(b,0,n);}
                if(count==0)throw new IOException("文件是空的");
            }
            String lower=a.name.toLowerCase(Locale.ROOT);
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(target.getPath(),bounds);
            if(bounds.outWidth>0&&bounds.outHeight>0){
                normalizeImage(target,bounds);a.kind="image";a.mime="image/jpeg";
            }else if(lower.endsWith(".pdf")||a.mime.equals("application/pdf")){
                if(target.length()>MAX_FILE)throw new IOException("PDF 最大支持 8 MB");
                try(InputStream head=new FileInputStream(target)){
                    byte[] b=new byte[5];if(head.read(b)!=5||!new String(b,StandardCharsets.US_ASCII).equals("%PDF-"))throw new IOException("PDF 文件格式不正确");
                }
                a.kind="pdf";a.mime="application/pdf";
            }else if(lower.endsWith(".docx")||a.mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")){
                if(target.length()>MAX_FILE)throw new IOException("DOCX 最大支持 8 MB");
                a.kind="text";a.text=docx(target);a.mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }else{
                if(a.mime.startsWith("image/"))throw new IOException("无法读取这张图片，请换成 JPG、PNG 或 WebP");
                if(lower.matches(".*\\.(doc|xls|xlsx|ppt|pptx|zip|rar|7z|apk|exe|mp3|mp4|wav)$"))throw new IOException("暂不支持此格式，请转换为 PDF、DOCX 或文本文件");
                if(target.length()>MAX_TEXT_BYTES)throw new IOException("文本文件最大支持 1 MB");
                a.kind="text";a.text=decodeText(read(target,MAX_TEXT_BYTES));a.mime="text/plain";
            }
            if(a.kind.equals("text")){
                if(a.text.trim().isEmpty())throw new IOException("文件中没有可读取的文字");
                if(a.text.length()>MAX_TEXT)throw new IOException("文档文字超过 12 万字符，请拆分文件后添加");
            }
            a.size=target.length();ok=true;return a;
        }finally{if(!ok)target.delete();}
    }
    static byte[] read(File f,int max)throws Exception{
        try(InputStream in=new FileInputStream(f)){return read(in,max);}
    }
    static byte[] read(InputStream in,int max)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("文件内容过大，请拆分后添加");out.write(b,0,n);}return out.toByteArray();
    }
    static String decodeText(byte[] bytes)throws Exception{
        String value;int n=bytes.length;
        if(n>=2&&((bytes[0]&255)==255&&(bytes[1]&255)==254||(bytes[0]&255)==254&&(bytes[1]&255)==255))value=new String(bytes,StandardCharsets.UTF_16);
        else{
            try{value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
            catch(CharacterCodingException e){
                try{value=Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
                catch(CharacterCodingException bad){throw new IOException("文件不是可读取的文本，请转换为 UTF-8 文本或 PDF");}
            }
        }
        if(value.startsWith("\ufeff"))value=value.substring(1);
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<32&&c!='\r'&&c!='\n'&&c!='\t')throw new IOException("文件包含二进制内容，请选择图片、PDF、DOCX 或文本文件");}
        return value;
    }
    static String docx(File f)throws Exception{
        try(ZipFile zip=new ZipFile(f)){
            ZipEntry e=zip.getEntry("word/document.xml");if(e==null)throw new IOException("DOCX 文件格式不正确");
            byte[] xml;try(InputStream in=zip.getInputStream(e)){xml=read(in,2*1048576);}
            XmlPullParser parser=Xml.newPullParser();parser.setInput(new ByteArrayInputStream(xml),null);
            StringBuilder out=new StringBuilder();boolean text=false;
            for(int event=parser.getEventType();event!=XmlPullParser.END_DOCUMENT;event=parser.nextToken()){
                if(event==XmlPullParser.DOCDECL)throw new IOException("不支持含外部声明的 DOCX");
                String tag=parser.getName();if(tag!=null&&tag.indexOf(':')>=0)tag=tag.substring(tag.indexOf(':')+1);
                if(event==XmlPullParser.START_TAG){
                    if("t".equals(tag))text=true;else if("tab".equals(tag))out.append('\t');else if("br".equals(tag)||"cr".equals(tag))out.append('\n');
                }else if(event==XmlPullParser.END_TAG){
                    if("t".equals(tag))text=false;else if("p".equals(tag))out.append('\n');else if("tc".equals(tag))out.append('\t');
                }else if(text&&(event==XmlPullParser.TEXT||event==XmlPullParser.CDSECT||event==XmlPullParser.ENTITY_REF||event==XmlPullParser.IGNORABLE_WHITESPACE)){
                    String value=parser.getText();if(value==null)throw new IOException("DOCX 包含无法解析的文字实体");out.append(value);
                }
                if(out.length()>MAX_TEXT)throw new IOException("文档文字超过 12 万字符，请拆分文件后添加");
            }
            return out.toString().trim();
        }catch(ZipException e){throw new IOException("DOCX 文件损坏或加密，请转换为 PDF 或文本");}
    }
    static void normalizeImage(File f,BitmapFactory.Options bounds)throws Exception{
        if((long)bounds.outWidth*bounds.outHeight>100000000L)throw new IOException("图片尺寸过大，请先缩小图片");
        int orientation=1;try(InputStream in=new FileInputStream(f)){orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bitmap=BitmapFactory.decodeFile(f.getPath(),options);if(bitmap==null)throw new IOException("图片解码失败");
        try{
            Matrix m=new Matrix();switch(orientation){
                case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;
                case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(-90);m.postScale(-1,1);break;case 8:m.setRotate(-90);break;
            }
            if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);if(rotated!=bitmap){bitmap.recycle();bitmap=rotated;}}
            if(bitmap.hasAlpha()){
                Bitmap opaque=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(opaque);c.drawColor(Color.WHITE);c.drawBitmap(bitmap,0,0,null);bitmap.recycle();bitmap=opaque;
            }
            byte[] bytes=null;
            for(int quality=88;quality>=48;quality-=20){ByteArrayOutputStream out=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out))throw new IOException("图片压缩失败");bytes=out.toByteArray();if(bytes.length<=MAX_IMAGE)break;}
            if(bytes==null||bytes.length>MAX_IMAGE)throw new IOException("压缩后图片仍超过 2 MB，请先缩小图片");
            try(OutputStream out=new FileOutputStream(f)){out.write(bytes);}
        }finally{bitmap.recycle();}
    }
    static void validate(List<Models.Attachment> files)throws IOException{
        if(files.size()>MAX_COUNT)throw new IOException("每条消息最多添加 6 个附件");
        long total=0;for(Models.Attachment a:files)total+=a.size;
        if(total>MAX_TURN)throw new IOException("本条消息附件合计不能超过 12 MB");
    }
    String data(Models.Attachment a)throws Exception{
        File f=file(a.id);if(!f.isFile())throw new IOException("附件已丢失，请重新添加："+a.name);
        byte[] bytes=read(f,a.kind.equals("image")?MAX_IMAGE:MAX_FILE);
        return "data:"+a.mime+";base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP);
    }
    Object content(Models.Message message)throws Exception{
        if(message.attachments.isEmpty())return message.text;
        StringBuilder text=new StringBuilder(message.text);JSONArray parts=new JSONArray();
        for(Models.Attachment a:message.attachments){
            if(a.kind.equals("text"))text.append("\n\n[附件：").append(a.name).append("]\n").append(a.text).append("\n[附件结束]\n");
            else if(a.kind.equals("image"))parts.put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",data(a))));
            else if(a.kind.equals("pdf"))parts.put(new JSONObject().put("type","file").put("file",new JSONObject().put("filename",a.name).put("file_data",data(a))));
            else throw new IOException("附件类型无法识别："+a.name);
        }
        if(parts.length()==0)return text.toString();
        JSONArray all=new JSONArray();if(text.length()>0)all.put(new JSONObject().put("type","text").put("text",text.toString()));
        for(int i=0;i<parts.length();i++)all.put(parts.get(i));return all;
    }
    void delete(Models.Attachment a){try{file(a.id).delete();}catch(IOException ignored){}}
    void prune(Set<String> retained){File[] files=root.listFiles();if(files!=null)for(File f:files)if(!retained.contains(f.getName()))f.delete();}
}
