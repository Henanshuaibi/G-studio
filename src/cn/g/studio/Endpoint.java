package cn.g.studio;
import java.net.URI;
final class Endpoint {
    static String normalize(String input,boolean allowHttp){
        String raw=input.trim();if(raw.isEmpty())throw new IllegalArgumentException("请填写 API 地址");
        try {
            URI u=new URI(raw);String scheme=u.getScheme();
            if(!"https".equalsIgnoreCase(scheme)&&!"http".equalsIgnoreCase(scheme))
                throw new IllegalArgumentException("地址需以 https:// 或 http:// 开头");
            if("http".equalsIgnoreCase(scheme)&&!allowHttp)
                throw new IllegalArgumentException("该地址使用 HTTP，请先打开“允许 HTTP”");
            if(u.getHost()==null||u.getHost().isEmpty()||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getFragment()!=null)
                throw new IllegalArgumentException("请填写纯 API 地址，不包含账号、查询参数或 #");
            String path=u.getRawPath();if(path==null)path="";
            while(path.endsWith("/"))path=path.substring(0,path.length()-1);
            if(path.endsWith("/chat/completions"))path=path.substring(0,path.length()-17);
            else if(path.endsWith("/images/generations"))path=path.substring(0,path.length()-19);
            else if(path.endsWith("/responses"))path=path.substring(0,path.length()-10);
            else if(path.endsWith("/videos"))path=path.substring(0,path.length()-7);
            else if(path.endsWith("/models"))path=path.substring(0,path.length()-7);
            if(path.isEmpty())path="/v1";
            return scheme.toLowerCase(java.util.Locale.ROOT)+"://"+u.getRawAuthority()+path;
        }catch(java.net.URISyntaxException e){throw new IllegalArgumentException("API 地址格式不正确");}
    }
    static String chat(String base,boolean http){return normalize(base,http)+"/chat/completions";}
    static String models(String base,boolean http){return normalize(base,http)+"/models";}
}
