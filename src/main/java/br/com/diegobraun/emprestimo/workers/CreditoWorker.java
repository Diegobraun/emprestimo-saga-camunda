package br.com.diegobraun.emprestimo.workers;

import java.math.BigDecimal;
import java.util.Map;

import br.com.diegobraun.emprestimo.proposta.Acompanhamento;
import br.com.diegobraun.emprestimo.sistemas.BureauCredito;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import org.springframework.stereotype.Component;

@Component
public class CreditoWorker {

    private final BureauCredito bureau;
    private final Acompanhamento acompanhamento;

    public CreditoWorker(BureauCredito bureau, Acompanhamento acompanhamento) {
        this.bureau = bureau;
        this.acompanhamento = acompanhamento;
    }

    @JobWorker(type = "analisar-credito")
    public Map<String, Object> analisar(@Variable String propostaId, @Variable String cliente, @Variable BigDecimal valor) {
        BureauCredito.Analise analise = bureau.analisar(cliente, valor);
        acompanhamento.registrar(propostaId, "crédito analisado: score " + analise.score() + " -> " + analise.decisao());
        return Map.of(
                "score", analise.score(),
                "decisaoCredito", analise.decisao().name(),
                "taxaMensal", analise.taxaMensal());
    }
}
