package ma.onda.rag;

import org.springframework.boot.SpringApplication;

public class TestOndaRagApplication {

    public static void main(String[] args) {
        SpringApplication.from(OndaRagApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
