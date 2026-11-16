package br.com.diegobraun.emprestimo.proposta;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import br.com.diegobraun.emprestimo.AppProperties;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.ClientException;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.UserTask;
import org.springframework.stereotype.Service;

@Service
public class PropostaService {

    public static final String PROCESSO = "contratacao-emprestimo";
    public static final String MENSAGEM_ASSINATURA = "contrato-assinado";

    private final CamundaClient camunda;
    private final Acompanhamento acompanhamento;
    private final AppProperties props;

    public PropostaService(CamundaClient camunda, Acompanhamento acompanhamento, AppProperties props) {
        this.camunda = camunda;
        this.acompanhamento = acompanhamento;
        this.props = props;
    }

    public PropostaAberta abrir(NovaProposta proposta) {
        String propostaId = UUID.randomUUID().toString();
        Map<String, Object> variaveis = new LinkedHashMap<>();
        variaveis.put("propostaId", propostaId);
        variaveis.put("cliente", proposta.cliente());
        variaveis.put("valor", proposta.valor());
        variaveis.put("parcelas", proposta.parcelas());
        variaveis.put("contaDestino", proposta.contaDestino());
        variaveis.put("prazoAssinatura", proposta.prazoAssinatura() != null
                ? proposta.prazoAssinatura() : props.prazoAssinaturaPadrao());
        ProcessInstanceEvent instancia = camunda.newCreateInstanceCommand()
                .bpmnProcessId(PROCESSO)
                .latestVersion()
                .variables(variaveis)
                .send()
                .join();
        acompanhamento.iniciada(propostaId, instancia.getProcessInstanceKey());
        return new PropostaAberta(propostaId, instancia.getProcessInstanceKey());
    }

    public boolean assinar(String propostaId) {
        try {
            camunda.newPublishMessageCommand()
                    .messageName(MENSAGEM_ASSINATURA)
                    .correlationKey(propostaId)
                    .messageId("assinatura-" + propostaId)
                    .timeToLive(Duration.ofMinutes(10))
                    .send()
                    .join();
            acompanhamento.registrar(propostaId, "assinatura recebida");
            return true;
        } catch (ClientException e) {
            if (String.valueOf(e.getMessage()).toLowerCase().contains("already")) {
                acompanhamento.registrar(propostaId, "assinatura duplicada ignorada");
                return false;
            }
            throw e;
        }
    }

    public Optional<Long> revisar(String propostaId, Revisao revisao) {
        Long instancia = acompanhamento.instancia(propostaId);
        if (instancia == null) {
            return Optional.empty();
        }
        List<UserTask> tarefas = camunda.newUserTaskSearchRequest()
                .filter(f -> f.processInstanceKey(instancia).state(UserTaskState.CREATED))
                .send()
                .join()
                .items();
        if (tarefas.isEmpty()) {
            return Optional.empty();
        }
        long tarefa = tarefas.getFirst().getUserTaskKey();
        camunda.newCompleteUserTaskCommand(tarefa)
                .variables(Map.of("aprovadoManualmente", revisao.aprovado(), "parecer", revisao.parecer()))
                .send()
                .join();
        acompanhamento.registrar(propostaId, "analista " + (revisao.aprovado() ? "aprovou" : "recusou") + ": " + revisao.parecer());
        return Optional.of(tarefa);
    }
}
