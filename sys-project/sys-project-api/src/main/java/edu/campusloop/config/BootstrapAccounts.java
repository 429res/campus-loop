package edu.campusloop.config;
import edu.campusloop.auth.PasswordService;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import javax.sql.DataSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
@Component
@ConditionalOnProperty(name="campus.bootstrap-enabled",havingValue="true")
public class BootstrapAccounts implements ApplicationRunner {
    private final UserMapper users; private final PasswordService passwords;
    private final String adminName,adminPassword,userName,userPassword;
    private final DataSource dataSource; private final ConfigurableApplicationContext context; private final boolean only, demo; private final TransactionTemplate transaction;
    public BootstrapAccounts(UserMapper users, PasswordService passwords,
        @Value("${campus.bootstrap-admin-username}") String adminName,@Value("${campus.bootstrap-admin-password}") String adminPassword,
        @Value("${campus.bootstrap-user-username}") String userName,@Value("${campus.bootstrap-user-password}") String userPassword,
        DataSource dataSource, ConfigurableApplicationContext context, PlatformTransactionManager manager,
        @Value("${campus.bootstrap-only}") boolean only,@Value("${campus.demo-enabled}") boolean demo) {
        this.users=users;this.passwords=passwords;this.adminName=adminName;this.adminPassword=adminPassword;
        this.userName=userName;this.userPassword=userPassword;
        this.dataSource=dataSource;this.context=context;this.only=only;this.demo=demo;this.transaction=new TransactionTemplate(manager);
    }
    @Override public void run(ApplicationArguments args) {
        if(adminName.equals(userName)) throw new IllegalArgumentException("管理员和用户账号必须不同");
        transaction.executeWithoutResult(status -> {
            // Seed before generated accounts; fail on occupied fixture IDs instead of attributing demo items to a real user.
            if(demo) {
                String[] fixtureNames={"demo_leaf","demo_sky","demo_moon"};
                for(int i=0;i<fixtureNames.length;i++) {
                    User byId=users.selectById(1001L+i);
                    User byName=users.selectOne(new QueryWrapper<User>().eq("username",fixtureNames[i]));
                    if((byId!=null && (!fixtureNames[i].equals(byId.getUsername()) || byId.getPasswordHash()!=null))
                        || (byName!=null && byName.getId()!=1001L+i))
                        throw new IllegalStateException("演示用户编号被其他记录占用，请使用新的独立数据库初始化");
                }
                new ResourceDatabasePopulator(new ClassPathResource("db/demo-data.sql")).execute(dataSource);
            }
            create(adminName,adminPassword,"校园管理员","ADMIN"); create(userName,userPassword,"校园同学","USER");
        });
        if(only) context.close();
    }
    private void create(String username,String password,String displayName,String role) {
        if(!username.matches("[A-Za-z0-9_.-]{3,64}")) throw new IllegalArgumentException("初始化账号需 3 至 64 位字母、数字或 _.-");
        User existing=users.selectOne(new QueryWrapper<User>().eq("username",username));
        if(existing!=null) {
            if(!role.equals(existing.getRole()) || existing.getPasswordHash()==null) throw new IllegalArgumentException("初始化账号与已有记录冲突");
            return; // Re-running initialization never resets passwords or overwrites account data.
        }
        User user=new User();user.setUsername(username);user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(displayName);user.setRole(role);user.setStatus("ACTIVE");user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));users.insert(user);
    }
}
