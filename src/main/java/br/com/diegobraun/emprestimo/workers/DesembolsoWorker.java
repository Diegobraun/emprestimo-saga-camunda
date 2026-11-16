package br.com.diegobraun.emprestimo.workers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import br.com.diegobraun.emprestimo.proposta.Acompanhamento;
import br.com.diegobraun.emprestimo.sistemas.CoreBancario;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.exception.CamundaError;
import org.springframework.stereotype.Component;

@Component
public class DesembolsoWorker {

    private final CoreBancario core;
    private final Acompanhamento acompanhamento;

    public DesembolsoWorker(CoreBancario core, Acompanhamento acompanhamento) {
        this.core = core;
        this.acompanhamento = acompanhamento;
    }

    @JobWorker(type = "desembolsar")
    public Map<String, Object> desembolsar(ActivatedJob job, @Variable String propostaId,
                                           @Variable String contaDestino, @Variable BigDecimal valor) {
        String operacaoId = propostaId + ":desembolso";
        try {
            CoreBancario.Lancamento lancamento = core.creditar(operacaoId, contaDestino, valor);
            acompanhamento.registrar(propostaId, "desembolso de " + valor.toPlainString() + " em " + contaDestino);
            return Map.of("operacaoDesembolso", lancamento.operacaoId());
        } catch (CoreBancario.ContaInexistenteException e) {
            acompanhamento.registrar(propostaId, "desembolso recusado: " + e.getMessage());
            throw CamundaError.bpmnError("DESEMBOLSO_RECUSADO", e.getMessage(), Map.of("motivo", e.getMessage()));
        } catch (CoreBancario.CoreIndisponivelException e) {
            acompanhamento.registrar(propostaId, "core indisponível, tentativas restantes: " + (job.getRetries() - 1));
            throw CamundaError.jobError(e.getMessage(), null, null, Duration.ofSeconds(3));
        }
    }
}
