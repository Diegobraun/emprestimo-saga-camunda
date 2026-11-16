package br.com.diegobraun.emprestimo;

import io.camunda.client.annotation.Deployment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@Deployment(resources = {"classpath*:/bpmn/*.bpmn", "classpath*:/bpmn/*.form"})
public class EmprestimoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmprestimoApplication.class, args);
    }
}
