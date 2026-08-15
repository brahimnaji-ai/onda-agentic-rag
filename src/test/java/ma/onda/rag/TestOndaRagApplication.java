package ma.onda.rag;

import org.springframework.boot.SpringApplication;

public class TestOndaAgenticRagApplication {

	public static void main(String[] args) {
		SpringApplication.from(OndaAgenticRagApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
