package br.com.diegobraun.emprestimo.proposta;

import java.util.LinkedHashMap;
import java.util.Map;

import br.com.diegobraun.emprestimo.sistemas.ContratoService;
import br.com.diegobraun.emprestimo.sistemas.CoreBancario;
import br.com.diegobraun.emprestimo.sistemas.LimiteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/propostas")
public class PropostaController {

    private final PropostaService service;
    private final Acompanhamento acompanhamento;
    private final LimiteService limites;
    private final ContratoService contratos;
    private final CoreBancario core;

    public PropostaController(PropostaService service, Acompanhamento acompanhamento, LimiteService limites,
                              ContratoService contratos, CoreBancario core) {
        this.service = service;
        this.acompanhamento = acompanhamento;
        this.limites = limites;
        this.contratos = contratos;
        this.core = core;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> abrir(@Valid @RequestBody NovaProposta proposta) {
        PropostaAberta aberta = service.abrir(proposta);
        return ResponseEntity.status(201).body(Map.of(
                "propostaId", aberta.propostaId(),
                "processInstanceKey", aberta.processInstanceKey(),
                "acompanhar", "/api/propostas/" + aberta.propostaId(),
                "operate", "http://localhost:8188/operate/processes/" + aberta.processInstanceKey()));
    }

    @PostMapping("/{id}/assinatura")
    public ResponseEntity<Map<String, Object>> assinar(@PathVariable String id) {
        boolean nova = service.assinar(id);
        return ResponseEntity.accepted().body(Map.of("propostaId", id, "assinaturaNova", nova));
    }

    @PostMapping("/{id}/revisao")
    public ResponseEntity<Map<String, Object>> revisar(@PathVariable String id, @Valid @RequestBody Revisao revisao) {
        return service.revisar(id, revisao)
                .<ResponseEntity<Map<String, Object>>>map(tarefa -> ResponseEntity.ok(Map.of("propostaId", id, "userTaskKey", tarefa)))
                .orElseGet(() -> ResponseEntity.status(409).header("Retry-After", "1")
                        .body(Map.of("erro", "nenhuma revisão pendente para a proposta (ou ainda não indexada)")));
    }

    @GetMapping("/{id}")
    public Map<String, Object> acompanhar(@PathVariable String id) {
        Map<String, Object> resposta = new LinkedHashMap<>();
        Acompanhamento.Registro registro = acompanhamento.registro(id);
        resposta.put("propostaId", id);
        resposta.put("desfecho", registro.desfecho());
        resposta.put("processInstanceKey", registro.processInstanceKey());
        resposta.put("reservaDeLimite", limites.reserva(id).orElse(null));
        resposta.put("contrato", contratos.contrato(id).orElse(null));
        resposta.put("desembolso", core.lancamento(id + ":desembolso").orElse(null));
        resposta.put("eventos", registro.eventos());
        return resposta;
    }
}
