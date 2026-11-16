package br.com.diegobraun.emprestimo;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.diegobraun.emprestimo.proposta.NovaProposta;
import br.com.diegobraun.emprestimo.proposta.PropostaAberta;
import br.com.diegobraun.emprestimo.proposta.PropostaService;
import br.com.diegobraun.emprestimo.proposta.Revisao;
import br.com.diegobraun.emprestimo.sistemas.Chaos;
import br.com.diegobraun.emprestimo.sistemas.ChaosService;
import br.com.diegobraun.emprestimo.sistemas.ContratoService;
import br.com.diegobraun.emprestimo.sistemas.CoreBancario;
import br.com.diegobraun.emprestimo.sistemas.LimiteService;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.Incident;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.assertions.ProcessInstanceAssert;
import io.camunda.process.test.api.assertions.ProcessInstanceSelectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@CamundaSpringProcessTest
class ContratacaoEmprestimoProcessTest {

    @Autowired
    PropostaService propostas;

    @Autowired
    CamundaClient client;

    @Autowired
    CamundaProcessTestContext processo;

    @Autowired
    LimiteService limites;

    @Autowired
    ContratoService contratos;

    @Autowired
    CoreBancario core;

    @Autowired
    ChaosService chaos;

    @BeforeAll
    static void configurar() {
        CamundaAssert.setAssertionTimeout(Duration.ofSeconds(30));
    }

    @AfterEach
    void desligarChaos() {
        chaos.definir(Chaos.desligado());
    }

    @Test
    void aprovadaAutomaticamenteEAssinadaViraContrato() {
        PropostaAberta p = abrir("cliente-bom-" + UUID.randomUUID(), "10000", "conta-1", "PT1H");

        instancia(p).isWaitingForMessage(PropostaService.MENSAGEM_ASSINATURA, p.propostaId());
        propostas.assinar(p.propostaId());

        instancia(p).isCompleted().hasCompletedElementsInOrder(
                "AnalisarCredito", "ReservarLimite", "GerarContrato", "AguardarAssinatura",
                "Desembolsar", "AtivarContrato", "DesfechoContratado", "FimContratado");
        assertThat(contratos.contrato(p.propostaId())).map(ContratoService.Contrato::status).contains(ContratoService.Status.ATIVO);
        assertThat(limites.reserva(p.propostaId())).map(LimiteService.Reserva::status).contains(LimiteService.Status.UTILIZADA);
        assertThat(core.lancamento(p.propostaId() + ":desembolso")).isPresent();
    }

    @Test
    void scoreBaixoRecusaSemTocarEmNada() {
        PropostaAberta p = abrir("cliente-ruim-" + UUID.randomUUID(), "10000", "conta-1", "PT1H");

        instancia(p).isCompleted().hasCompletedElements("FimRecusadaCredito").hasNotActivatedElements("ReservarLimite");
        assertThat(limites.reserva(p.propostaId())).isEmpty();
    }

    @Test
    void scoreMedioVaiParaAnalistaQueAprova() {
        PropostaAberta p = abrir("cliente-medio-" + UUID.randomUUID(), "10000", "conta-1", "PT1H");

        instancia(p).hasActiveElements("RevisarProposta");
        revisar(p, true);

        instancia(p).isWaitingForMessage(PropostaService.MENSAGEM_ASSINATURA, p.propostaId());
        assertThat(limites.reserva(p.propostaId())).isPresent();
    }

    @Test
    void assinaturaQueNaoChegaExpiraECompensa() {
        PropostaAberta p = abrir("cliente-bom-" + UUID.randomUUID(), "10000", "conta-1", "PT1H");
        instancia(p).isWaitingForMessage(PropostaService.MENSAGEM_ASSINATURA, p.propostaId());

        processo.increaseTime(Duration.ofHours(1));

        instancia(p).isCompleted().hasCompletedElements("LiberarLimite", "CancelarContrato", "FimExpirada")
                .hasNotActivatedElements("Desembolsar");
        assertThat(limites.reserva(p.propostaId())).map(LimiteService.Reserva::status).contains(LimiteService.Status.LIBERADA);
        assertThat(contratos.contrato(p.propostaId())).map(ContratoService.Contrato::status).contains(ContratoService.Status.CANCELADO);
    }

    @Test
    void desembolsoRecusadoCompensaReservaEContrato() {
        PropostaAberta p = abrir("cliente-bom-" + UUID.randomUUID(), "10000", "conta-que-nao-existe", "PT1H");
        instancia(p).isWaitingForMessage(PropostaService.MENSAGEM_ASSINATURA, p.propostaId());

        propostas.assinar(p.propostaId());

        instancia(p).isCompleted().hasCompletedElements("LiberarLimite", "CancelarContrato", "FimCancelada");
        assertThat(limites.reserva(p.propostaId())).map(LimiteService.Reserva::status).contains(LimiteService.Status.LIBERADA);
    }

    @Test
    void contratoRecusadoDesfazSoAReserva() {
        PropostaAberta p = abrir("cliente-bom-restricao-" + UUID.randomUUID(), "10000", "conta-1", "PT1H");

        instancia(p).isCompleted()
                .hasCompletedElementsInOrder("ReservarLimite", "LiberarLimite", "DesfechoContratoRecusado", "FimContratoRecusado")
                .hasNotActivatedElements("CancelarContrato", "AguardarAssinatura");
        assertThat(limites.reserva(p.propostaId())).map(LimiteService.Reserva::status).contains(LimiteService.Status.LIBERADA);
        assertThat(contratos.contrato(p.propostaId())).isEmpty();
    }

    @Test
    void valorAcimaDoLimiteTerminaSemLimite() {
        PropostaAberta p = abrir("cliente-bom-" + UUID.randomUUID(), "150000", "conta-1", "PT1H");

        instancia(p).hasActiveElements("RevisarProposta");
        revisar(p, true);

        instancia(p).isCompleted().hasCompletedElements("FimSemLimite").hasNotActivatedElements("LiberarLimite");
    }

    @Test
    void assinaturaDuplicadaEhIgnorada() {
        PropostaAberta p = abrir("cliente-bom-" + UUID.randomUUID(), "10000", "conta-1", "PT1H");
        instancia(p).isWaitingForMessage(PropostaService.MENSAGEM_ASSINATURA, p.propostaId());

        assertThat(propostas.assinar(p.propostaId())).isTrue();
        assertThat(propostas.assinar(p.propostaId())).isFalse();

        instancia(p).isCompleted().hasCompletedElement("Desembolsar", 1);
    }

    @Test
    void coreForaDoArViraIncidenteQueOperadorResolve() {
        chaos.definir(new Chaos(true, false));
        PropostaAberta p = abrir("cliente-bom-" + UUID.randomUUID(), "10000", "conta-2", "PT1H");
        instancia(p).isWaitingForMessage(PropostaService.MENSAGEM_ASSINATURA, p.propostaId());
        propostas.assinar(p.propostaId());

        instancia(p).hasActiveIncidents().hasActiveElements("Desembolsar");

        chaos.definir(Chaos.desligado());
        Incident incidente = incidente(p);
        client.newUpdateRetriesCommand(incidente.getJobKey()).retries(1).send().join();
        client.newResolveIncidentCommand(incidente.getIncidentKey()).send().join();

        instancia(p).isCompleted().hasNoActiveIncidents().hasCompletedElements("FimContratado");
    }

    private PropostaAberta abrir(String cliente, String valor, String conta, String prazo) {
        return propostas.abrir(new NovaProposta(cliente, new BigDecimal(valor), 12, conta, prazo));
    }

    private ProcessInstanceAssert instancia(PropostaAberta p) {
        return CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(p.processInstanceKey()));
    }

    private void revisar(PropostaAberta p, boolean aprovado) {
        long limite = System.currentTimeMillis() + 30_000;
        Optional<Long> tarefa = Optional.empty();
        while (tarefa.isEmpty() && System.currentTimeMillis() < limite) {
            tarefa = propostas.revisar(p.propostaId(), new Revisao(aprovado, "ok pelo teste"));
            if (tarefa.isEmpty()) {
                pausa();
            }
        }
        assertThat(tarefa).as("tarefa de revisão encontrada").isPresent();
    }

    private Incident incidente(PropostaAberta p) {
        long limite = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < limite) {
            List<Incident> incidentes = client.newIncidentSearchRequest()
                    .filter(f -> f.processInstanceKey(p.processInstanceKey()))
                    .send()
                    .join()
                    .items();
            if (!incidentes.isEmpty()) {
                return incidentes.getFirst();
            }
            pausa();
        }
        throw new AssertionError("incidente não apareceu na busca");
    }

    private void pausa() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
