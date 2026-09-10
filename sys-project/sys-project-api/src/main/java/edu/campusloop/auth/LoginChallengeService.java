package edu.campusloop.auth;
import org.springframework.stereotype.Service;
import edu.campusloop.common.ApiException;
import java.util.*;
import java.security.SecureRandom;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
@Service public class LoginChallengeService {
 private record Fail(int count,long expires){} private record Challenge(String answer,String address,long expires){}
 private final Map<String,Fail> failures=new HashMap<>();private final Map<String,Challenge> challenges=new HashMap<>();private final SecureRandom rng=new SecureRandom();
 public record Image(String id,String image,int expiresIn){}
 private void clean(){long now=System.currentTimeMillis();failures.values().removeIf(f->f.expires<now);challenges.values().removeIf(c->c.expires<now);if(failures.size()>10000||challenges.size()>10000)throw new ApiException(429,"请求较多，请稍后再试");}
 public synchronized boolean required(String account,String address){clean();return failures.getOrDefault(account,new Fail(0,0)).count>=2||failures.getOrDefault("IP:"+address,new Fail(0,0)).count>=2;}
 public synchronized void failed(String account,String address){clean();for(String key:java.util.List.of(account,"IP:"+address)){var f=failures.getOrDefault(key,new Fail(0,0));failures.put(key,new Fail(f.count+1,System.currentTimeMillis()+900000));}}
 public synchronized void success(String account,String address){failures.remove(account);failures.remove("IP:"+address);}
 public synchronized void verify(String id,String answer,String address){clean();var c=challenges.remove(id);if(c==null||!c.address.equals(address)||answer==null||!c.answer.equalsIgnoreCase(answer.trim()))throw new ApiException(428,"请完成图片验证码，或刷新后重试");}
 public synchronized Image image(String address){clean();String chars="23456789ABCDEFGHJKMNPQRSTUVWXYZ",answer="";for(int i=0;i<5;i++)answer+=chars.charAt(rng.nextInt(chars.length()));String id=UUID.randomUUID().toString();challenges.put(id,new Challenge(answer,address,System.currentTimeMillis()+120000));try{
  var image=new BufferedImage(180,60,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(new Color(242,246,252));g.fillRect(0,0,180,60);for(int i=0;i<18;i++){g.setColor(new Color(rng.nextInt(160)+60,rng.nextInt(160)+60,rng.nextInt(160)+60));g.drawLine(rng.nextInt(180),rng.nextInt(60),rng.nextInt(180),rng.nextInt(60));}g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,32));for(int i=0;i<5;i++){g.setColor(new Color(40+rng.nextInt(80),30+rng.nextInt(80),70+rng.nextInt(80)));g.drawString(answer.substring(i,i+1),12+i*32,38+rng.nextInt(10));}g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return new Image(id,"data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray()),120);
 }catch(Exception e){throw new ApiException(503,"验证码暂时不可用");}}
}
