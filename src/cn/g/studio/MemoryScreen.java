package cn.g.studio;
import android.text.*;
import android.widget.*;
import java.util.*;

final class MemoryScreen {
    static void render(MainActivity a){
        MemoryStore memory=a.app.memory;a.header("记忆",()->{a.page="chat";a.render();});LinearLayout body=a.scrollBody();
        a.add(body,a.label("让新的对话也了解你",a.small?20:25,MainActivity.INK),8);
        a.add(body,a.label("保存你的偏好、背景和常用信息。启用后，每次聊天会将这些记忆发送给当前选择的 API 服务。新对话也可使用。",14,MainActivity.MUTED),10);
        Switch enabled=new Switch(a);enabled.setText("在聊天中使用记忆");enabled.setTextColor(MainActivity.INK);enabled.setChecked(memory.enabled());a.add(body,enabled,18);
        enabled.setOnCheckedChangeListener((button,use)->{try{memory.setEnabled(use);}catch(Exception e){button.setOnCheckedChangeListener(null);button.setChecked(memory.enabled());a.error(e);a.render();}});
        a.add(body,a.button("＋ 添加记忆",()->edit(a,"","")),14);
        ArrayList<MemoryStore.Entry> entries=memory.list();a.add(body,a.label("已保存 "+entries.size()+" 条 · 消息操作里也可记入记忆",12,MainActivity.MUTED),12);
        for(MemoryStore.Entry entry:entries){
            LinearLayout card=a.column();card.setPadding(a.dp(14),a.dp(12),a.dp(14),a.dp(12));card.setBackground(a.background(MainActivity.CARD,16));TextView text=a.label(entry.text,15,MainActivity.INK);text.setMaxLines(6);text.setEllipsize(TextUtils.TruncateAt.END);card.addView(text);
            LinearLayout controls=a.row();controls.addView(a.button("编辑",()->edit(a,entry.id,entry.text)),new LinearLayout.LayoutParams(0,-2,1));
            controls.addView(a.button("删除",()->a.sheet().setTitle("删除这条记忆？").setMessage(entry.text.length()>160?entry.text.substring(0,160)+"…":entry.text).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{try{memory.delete(entry.id);a.render();}catch(Exception e){a.error(e);}}).show()),new LinearLayout.LayoutParams(0,-2,1));a.add(card,controls,10);a.add(body,card,12);
        }
        if(entries.isEmpty())a.add(body,a.label("例如：我在中国大陆，使用 iQOO 13；回答请用简洁中文。",14,MainActivity.MUTED),22);
        else a.add(body,a.button("清空全部记忆",()->a.sheet().setTitle("清空全部记忆？").setMessage("已保存的记忆会删除，聊天记录保留。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{try{memory.clear();a.render();}catch(Exception e){a.error(e);}}).show()),20);
        a.add(body,a.label("记忆由你选择保存，不会自动从聊天中提取。关闭开关会保留本机记忆，并停止在之后的聊天请求中发送；已发给服务商的内容无法撤回。",12,MainActivity.MUTED),18);
    }
    static void edit(MainActivity a,String id,String initial){
        LinearLayout box=a.column();EditText text=a.field(box,"记忆内容",initial,"例如：我喜欢简洁的中文回答。",true);text.setMinLines(a.small?4:6);text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MemoryStore.MAX_TEXT)});
        a.add(box,a.label("单条最多 3000 字符；总计最多 100 条、24000 字符。保存后可随时编辑或删除。",12,MainActivity.MUTED),10);
        TextView save=a.button("保存记忆",()->{});a.add(box,save,16);
        SheetDialog dialog=a.sheet().setTitle(id.isEmpty()?"添加记忆":"编辑记忆").setView(box).setNegativeButton("取消",null).create();
        save.setOnClickListener(v->{if(dialog.isClosing())return;try{a.app.memory.save(id,text.getText().toString());save.setEnabled(false);a.hideKeyboard();dialog.dismiss();a.toast("记忆已保存");if(a.page.equals("memory"))a.render();}catch(Exception e){a.error(e);}});dialog.show();
    }
}
