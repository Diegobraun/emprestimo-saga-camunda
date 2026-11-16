package br.com.diegobraun.emprestimo.workers;

import java.math.BigDecimal;
import java.util.Map;

import br.com.diegobraun.emprestimo.proposta.Acompanhamento;
import br.com.diegobraun.emprestimo.sistemas.LimiteService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.exception.CamundaError;
import org.springframework.stereotype.Component;

@Component
public class LimiteWorker {

    private final LimiteService limites;
    private final Acompanhamento acompanhamento;

    public LimiteWorker(LimiteService limites, Acompanhamento acompanhamento) {
        this.limites = limites;
        this.acompanhamento = acompanhamento;
    }

    @JobWorker(type = "reservar-limite")
    public void reservar(@Variable String propostaId, @Variable String cliente, @Variable BigDecimal valor) {
        try {
            limites.reservar(propostaId, cliente, valor);
            acompanhamento.registrar(propostaId, "limite reservado: " + valor.toPlainString());
        } catch (LimiteService.LimiteInsuficienteException e) {
            acompanhamento.registrar(propostaId, "sem limite: " + e.getMessage());
            throw CamundaError.bpmnError("LIMITE_INSUFICIENTE", e.getMessage(), Map.of("motivo", e.getMessage()));
        }
    }

    @JobWorker(type = "liberar-limite")
    public void liberar(@Variable String propostaId) {
        limites.liberar(propostaId);
        acompanhamento.registrar(propostaId, "COMPENSAÇÃO: limite liberado");
    }
}
