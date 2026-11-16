package br.com.diegobraun.emprestimo.workers;

import java.util.Map;

import br.com.diegobraun.emprestimo.proposta.Acompanhamento;
import io.camunda.client.annotation.CustomHeaders;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import org.springframework.stereotype.Component;

@Component
public class DesfechoWorker {

    private final Acompanhamento acompanhamento;

    public DesfechoWorker(Acompanhamento acompanhamento) {
        this.acompanhamento = acompanhamento;
    }

    @JobWorker(type = "registrar-desfecho")
    public void registrar(@Variable String propostaId, @CustomHeaders Map<String, String> headers) {
        acompanhamento.desfecho(propostaId, headers.getOrDefault("desfecho", "DESCONHECIDO"));
    }
}
