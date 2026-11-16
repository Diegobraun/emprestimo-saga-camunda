package br.com.diegobraun.emprestimo.workers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import br.com.diegobraun.emprestimo.proposta.Acompanhamento;
import br.com.diegobraun.emprestimo.sistemas.ContratoService;
import br.com.diegobraun.emprestimo.sistemas.LimiteService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.exception.CamundaError;
import org.springframework.stereotype.Component;

@Component
public class ContratoWorker {

    private final ContratoService contratos;
    private final LimiteService limites;
    private final Acompanhamento acompanhamento;

    public ContratoWorker(ContratoService contratos, LimiteService limites, Acompanhamento acompanhamento) {
        this.contratos = contratos;
        this.limites = limites;
        this.acompanhamento = acompanhamento;
    }

    @JobWorker(type = "gerar-contrato")
    public Map<String, Object> gerar(@Variable String propostaId, @Variable String cliente, @Variable BigDecimal valor,
                                     @Variable Integer parcelas, @Variable BigDecimal taxaMensal) {
        try {
            ContratoService.Contrato contrato = contratos.gerar(propostaId, cliente, valor, parcelas, taxaMensal);
            acompanhamento.registrar(propostaId, "contrato " + contrato.numero() + " gerado, aguardando assinatura");
            return Map.of("numeroContrato", contrato.numero());
        } catch (ContratoService.RestricaoJuridicaException e) {
            acompanhamento.registrar(propostaId, "contrato recusado: " + e.getMessage());
            throw CamundaError.bpmnError("CONTRATO_RECUSADO", e.getMessage(), Map.of("motivo", e.getMessage()));
        }
    }

    @JobWorker(type = "cancelar-contrato")
    public void cancelar(@Variable String propostaId) {
        contratos.cancelar(propostaId);
        acompanhamento.registrar(propostaId, "COMPENSAÇÃO: contrato cancelado");
    }

    @JobWorker(type = "ativar-contrato")
    public void ativar(@Variable String propostaId) {
        try {
            contratos.ativar(propostaId);
            limites.utilizar(propostaId);
            acompanhamento.registrar(propostaId, "contrato ativado");
        } catch (IllegalStateException e) {
            acompanhamento.registrar(propostaId, "falha ao ativar, Camunda vai tentar de novo: " + e.getMessage());
            throw CamundaError.jobError(e.getMessage(), null, null, Duration.ofSeconds(5));
        }
    }
}
