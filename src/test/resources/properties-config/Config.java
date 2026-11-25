import java.io.FileInputStream;
import java.util.Properties;

public class Config {

    public static Properties fromFile() throws Exception {
        Properties props = new Properties();
        props.load(new FileInputStream("config.properties"));
        return props;
    }

    public static Properties fromClasspath() throws Exception {
        Properties props = new Properties();
        props.load(Config.class.getClassLoader().getResourceAsStream("app.properties"));
        return props;
    }
}
