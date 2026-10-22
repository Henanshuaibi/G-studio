package cn.g.studio;
import static cn.g.studio.CoreCheck.check;
import static cn.g.studio.CoreCheck.fails;
import android.graphics.*;
import android.media.ExifInterface;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class AttachmentCheck {
    static Models.Attachment add(AttachmentStore store,String name,String mime,byte[] bytes)throws Exception{return store.importFile(name,mime,new ByteArrayInputStream(bytes));}
    static byte[] utf(String value){return value.getBytes(StandardCharsets.UTF_8);}
    static byte[] docx(String xml)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){
            zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(utf(xml));zip.closeEntry();
        }return out.toByteArray();
    }
    static void run(File dir)throws Exception{
        LocalStore local=new LocalStore(new File(dir,"attachments-"+UUID.randomUUID()),new LocalStore.Vault(){public String seal(String s){return s;}public String open(String s){return s;}});
        AttachmentStore store=local.attachments;Models.Provider p=CoreCheck.provider();
        Models.Attachment txt=add(store,"../../说明.md","text/markdown",utf("\ufeff# 你好\n说明文字🌿"));
        check(txt.kind.equals("text")&&txt.name.equals("说明.md")&&txt.text.equals("# 你好\n说明文字🌿"),"UTF8 BOM, Unicode, path stripping");
        Models.Attachment gb=add(store,"中文.txt","text/plain","中文编码".getBytes(Charset.forName("GB18030")));
        check(gb.text.equals("中文编码"),"GB18030 fallback");
        Models.Attachment u16=add(store,"utf16.txt","text/plain","第二种编码".getBytes(StandardCharsets.UTF_16));
        check(u16.text.equals("第二种编码"),"UTF16 BOM");
        Models.Attachment word=add(store,"报告.docx","application/octet-stream",docx("<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>第一段 &amp; 细节</w:t></w:r></w:p><w:p><w:r><w:t>第二段</w:t><w:tab/><w:t>尾部</w:t></w:r></w:p></w:body></w:document>"));
        check(word.kind.equals("text")&&word.text.equals("第一段 & 细节\n第二段\t尾部"),"DOCX paragraphs, entities and tabs");
        Models.Message textOnly=new Models.Message("user","总结文档");textOnly.attachments.add(word);textOnly.attachments.add(txt);
        Object textContent=store.content(textOnly);check(textContent instanceof String&&((String)textContent).contains("报告.docx")&&((String)textContent).contains("第二段"),"text attachments remain plain text for compatible relays");
        byte[] png;Bitmap original=Bitmap.createBitmap(3000,1200,Bitmap.Config.ARGB_8888);
        try{original.eraseColor(Color.TRANSPARENT);Canvas c=new Canvas(original);Paint paint=new Paint();paint.setColor(Color.RED);c.drawRect(100,100,1000,1000,paint);ByteArrayOutputStream out=new ByteArrayOutputStream();original.compress(Bitmap.CompressFormat.PNG,100,out);png=out.toByteArray();}finally{original.recycle();}
        Models.Attachment image=add(store,"图片.png","image/png",png);
        check(image.kind.equals("image")&&image.mime.equals("image/jpeg")&&image.size<=AttachmentStore.MAX_IMAGE,"image normalized to bounded JPEG");
        Bitmap normalized=BitmapFactory.decodeFile(store.file(image.id).getPath());
        check(normalized.getWidth()<=2048&&normalized.getHeight()<=2048,"image dimension bound");
        check(Color.red(normalized.getPixel(0,0))>245&&Color.green(normalized.getPixel(0,0))>245,"transparent images use white JPEG background");normalized.recycle();
        File rotatedFile=new File(local.root,"orientation.jpg");
        // Build a real EXIF APP1 fixture without the app-context-only EXIF writer.
        byte[] description=utf("PRIVATE_EXIF_TEST\0"),jpeg=Files.readAllBytes(store.file(image.id).toPath());
        java.nio.ByteBuffer tiff=java.nio.ByteBuffer.allocate(38+description.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8).putShort((short)2);
        tiff.putShort((short)0x10e).putShort((short)2).putInt(description.length).putInt(38);
        tiff.putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)6).putShort((short)0).putInt(0).put(description);
        try(OutputStream out=new FileOutputStream(rotatedFile)){
            out.write(jpeg,0,2);out.write(255);out.write(225);int length=tiff.array().length+8;out.write(length>>8);out.write(length&255);
            out.write(new byte[]{'E','x','i','f',0,0});out.write(tiff.array());out.write(jpeg,2,jpeg.length-2);
        }
        Models.Attachment rotated;try(InputStream in=new FileInputStream(rotatedFile)){rotated=store.importFile("旋转.jpg","image/jpeg",in);}
        normalized=BitmapFactory.decodeFile(store.file(rotated.id).getPath());check(normalized.getHeight()>normalized.getWidth(),"EXIF orientation applied");normalized.recycle();
        check(!new String(Files.readAllBytes(store.file(rotated.id).toPath()),StandardCharsets.ISO_8859_1).contains("PRIVATE_EXIF_TEST"),"image metadata stripped");
        byte[] pdf=utf("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n");
        Models.Attachment document=add(store,"示例.pdf","application/pdf",pdf);
        Models.Message mixed=new Models.Message("user","对比图片和文档");mixed.attachments.add(image);mixed.attachments.add(document);mixed.attachments.add(word);
        ArrayList<Models.Message> history=new ArrayList<>();history.add(mixed);
        JSONObject request=ApiClient.request(p,history,store);JSONArray parts=request.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        check(parts.length()==3&&parts.getJSONObject(0).getString("type").equals("text"),"multimodal text first");
        check(parts.getJSONObject(0).getString("text").contains(word.text),"DOCX full text in mixed request");
        check(parts.getJSONObject(1).getString("type").equals("image_url")&&parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"),"image data URL schema");
        JSONObject filePart=parts.getJSONObject(2);check(filePart.getString("type").equals("file")&&filePart.getJSONObject("file").getString("filename").equals("示例.pdf"),"PDF content part schema");
        String data=filePart.getJSONObject("file").getString("file_data");check(Arrays.equals(Base64.decode(data.substring(data.indexOf(',')+1),Base64.DEFAULT),pdf),"PDF bytes intact in base64");
        Models.Message imageOnly=new Models.Message("user","");imageOnly.attachments.add(image);
        check(((JSONArray)store.content(imageOnly)).getJSONObject(0).getString("type").equals("image_url"),"attachment-only message");
        try(CoreCheck.Server server=new CoreCheck.Server(200,"application/json","{\"choices\":[{\"message\":{\"content\":\"看到了附件\"},\"finish_reason\":\"stop\"}]}")){
            p.base=server.base();ApiClient.Result response=ApiClient.chat(p,request,new ApiClient.Call(),(a,b)->{});server.await();
            JSONArray wire=new JSONObject(server.request).getJSONArray("messages").getJSONObject(0).getJSONArray("content");
            check(response.text.equals("看到了附件")&&wire.getJSONObject(1).getJSONObject("image_url").getString("url").equals(parts.getJSONObject(1).getJSONObject("image_url").getString("url")),"real HTTP carries image payload");
            check(wire.getJSONObject(2).getJSONObject("file").getString("file_data").equals(data),"real HTTP carries PDF payload");
        }
        Models.Chat chat=new Models.Chat();chat.messages.add(mixed);chat.draftAttachments.add(txt);chat.draft="待发送";local.saveChat(chat);
        Models.Chat restored=local.loadChat(chat.id);
        check(restored.messages.get(0).attachments.size()==3&&restored.draftAttachments.get(0).text.equals(txt.text),"attachments and draft metadata survive disk reload");
        check(ApiClient.request(p,restored.messages,store).getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(2).getJSONObject("file").getString("file_data").equals(data),"resumed history still sends original attachment");
        String disk=new String(Files.readAllBytes(new File(local.chats,chat.id+".json").toPath()),StandardCharsets.UTF_8);
        check(!disk.contains("base64,")&&!disk.contains(p.key),"binary attachments and credentials excluded from chat JSON");
        Models.Chat draftOnly=new Models.Chat();draftOnly.draftAttachments.add(gb);local.saveChat(draftOnly);
        check(local.listChats().size()==2,"attachment-only drafts appear in history");
        Models.Chat old=Models.Chat.from(new JSONObject("{\"id\":\"old\",\"messages\":[{\"role\":\"user\",\"text\":\"旧对话\"}]}"));
        check(old.draftAttachments.isEmpty()&&old.messages.get(0).attachments.isEmpty(),"0.1 chat migration needs no destructive reset");
        history.add(new Models.Message("assistant","回答"));history.add(new Models.Message("user","下一轮"));p.contextTurns=1;
        check(ApiClient.request(p,history,store).getJSONArray("messages").length()==1,"context trimming excludes old binary attachment");p.contextTurns=10;
        fails(()->store.file("../../secret"),"编号");
        fails(()->add(store,"bad.pdf","application/pdf",utf("not a PDF")),"格式");
        fails(()->add(store,"bad.jpg","image/jpeg",utf("not an image")),"图片");
        fails(()->add(store,"binary.bin","application/octet-stream",new byte[]{1,2,0,10}),"二进制");
        fails(()->add(store,"empty.txt","text/plain",new byte[0]),"空");
        fails(()->add(store,"old.doc","application/msword",utf("legacy")),"暂不支持");
        fails(()->add(store,"bad.docx","application/octet-stream",new byte[]{1,2,3}),"DOCX");
        fails(()->add(store,"external.docx","application/octet-stream",docx("<!DOCTYPE document [<!ENTITY x SYSTEM \"file:///nonexistent\">]><document><t>&x;</t></document>")),"声明");
        char[] many=new char[AttachmentStore.MAX_TEXT+1];Arrays.fill(many,'字');
        fails(()->add(store,"too-long.txt","text/plain",utf(new String(many))),"12 万");
        fails(()->add(store,"too-large.txt","text/plain",new byte[AttachmentStore.MAX_TEXT_BYTES+1]),"1 MB");
        ArrayList<Models.Attachment> overCount=new ArrayList<>();for(int i=0;i<7;i++)overCount.add(txt);
        fails(()->AttachmentStore.validate(overCount),"6 个");
        Models.Attachment huge=new Models.Attachment();huge.kind="pdf";huge.size=AttachmentStore.MAX_TURN+1;
        fails(()->AttachmentStore.validate(Arrays.asList(huge)),"12 MB");
        Models.Message hugeMessage=new Models.Message("user","test");hugeMessage.attachments.add(huge);
        fails(()->ApiClient.request(p,Arrays.asList(hugeMessage),store),"上下文附件");
        fails(()->ApiClient.request(p,Arrays.asList(mixed)),"存储不可用");
        Models.Attachment missing=add(store,"missing.pdf","application/pdf",pdf);store.delete(missing);
        Models.Message missingMessage=new Models.Message("user","test");missingMessage.attachments.add(missing);
        fails(()->store.content(missingMessage),"丢失");
        local.pruneAttachments();
        check(store.file(image.id).exists()&&store.file(document.id).exists()&&store.file(txt.id).exists()&&store.file(gb.id).exists(),"cleanup retains sent and draft references across chats");
        check(!store.file(rotated.id).exists()&&!store.file(u16.id).exists(),"orphan cleanup removes unused attachments");
        local.deleteChat(chat.id);local.pruneAttachments();
        check(!store.file(image.id).exists()&&!store.file(document.id).exists()&&store.file(gb.id).exists(),"deleting chat reclaims only its attachments");
        local.deleteChat(draftOnly.id);local.pruneAttachments();check(store.root.list().length==0,"attachments fully reclaimed when no chat references remain");
    }
}
