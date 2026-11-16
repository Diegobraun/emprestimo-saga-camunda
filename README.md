# emprestimo-saga-camunda

Saga de contratação de empréstimo orquestrada pelo Camunda 8 (Zeebe). O fluxo fica num diagrama BPMN e cada passo é um job worker Spring Boot. Os sistemas externos (bureau de crédito, limites, contratos, core bancário) são simulados dentro da aplicação, com chaos para forçar falhas.

## Rodando

Pré-requisitos: Java 21+, Maven, Docker e `jq` (para a demo).

```bash
docker compose up -d
mvn spring-boot:run
bash scripts/demo.sh
```

| O quê | Endereço |
|---|---|
| API da aplicação | http://localhost:8085 |
| Operate (instâncias, incidentes) | http://localhost:8188/operate (demo/demo) |
| Tasklist (tarefa do analista) | http://localhost:8188/tasklist (demo/demo) |
| gRPC do Zeebe | localhost:26700 |

As portas fogem do padrão (8080, 8088, 26500, 9200) porque outros projetos locais já usam essas.

Testes (sobem um Camunda próprio via Testcontainers, então precisam do Docker):

```bash
mvn test
```

## O processo

O arquivo é `src/main/resources/bpmn/contratacao-emprestimo.bpmn`. Abra no Camunda Modeler para ver o diagrama.

```
 (início)
    │
 [Analisar crédito]
    │
 <Decisão?> ─recusada──► [Registrar recusa] ─► (Recusada pelo crédito)
    │    └─revisão (padrão)─► [Revisar proposta 👤] ─► <Aprovou?> ─não─► [Registrar recusa] ─► (Recusada pelo analista)
    │aprovada                                              │sim
    ▼◄─────────────────────────────────────────────────────┘
 [Reservar limite] ─(erro LIMITE_INSUFICIENTE)─► [Registrar recusa] ─► (Sem limite)
    │  ⟲ compensa: [Liberar limite]
 [Gerar contrato] ─(erro CONTRATO_RECUSADO)─► ⟲ compensar ─► [Registrar recusa do contrato] ─► (Contrato recusado)
    │  ⟲ compensa: [Cancelar contrato]
 [Aguardar assinatura ✉] ─(⏱ prazoAssinatura)─► ⟲ compensar tudo ─► [Registrar expiração] ─► (Assinatura expirada)
    │
 [Desembolsar] ─(erro DESEMBOLSO_RECUSADO)─► ⟲ compensar tudo ─► [Registrar cancelamento] ─► (Cancelada)
    │
 [Ativar contrato]
    │
 [Registrar contratação] ─► (Empréstimo contratado)
```

## Como a saga está montada

A teoria clássica de saga separa os passos em três tipos, e o processo segue essa divisão:

| Tipo | Passos | Regra |
|---|---|---|
| **Compensável** | Reservar limite, Gerar contrato | têm uma operação de desfazer ligada a eles |
| **Pivô** | Desembolsar | ponto sem volta: dinheiro na conta do cliente não se "compensa" com um clique |
| **Repetível** | Ativar contrato | depois do pivô, não pode falhar de vez: repete até dar certo, ou vira incidente para uma pessoa |

Por isso a ordem importa. Tudo o que pode ser desfeito vem **antes** do desembolso, e depois dele só sobra o que é garantido completar. Se o desembolso fosse o primeiro passo, uma falha na geração do contrato deixaria dinheiro na conta sem contrato.

## Elemento por elemento

### Service task e job worker
Cada caixa com engrenagem tem um `zeebe:taskDefinition type="..."`. O motor **não chama** a aplicação: ele cria um job, e o worker anotado com `@JobWorker(type = "...")` busca o job, executa e devolve variáveis.

```java
@JobWorker(type = "analisar-credito")
public Map<String, Object> analisar(@Variable String propostaId, @Variable String cliente, @Variable BigDecimal valor)
```

O `Map` devolvido vira variável do processo (`score`, `decisaoCredito`, `taxaMensal`), e o gateway seguinte decide o caminho com elas.

**Todo worker precisa ser idempotente.** O Zeebe entrega pelo menos uma vez: se o worker demora mais que o timeout do job, ou cai depois de executar e antes de confirmar, o job volta e roda de novo. Por isso cada sistema usa o `propostaId` como chave (`LimiteService.reservar`, `ContratoService.gerar`, `CoreBancario.creditar` com `{propostaId}:desembolso`). Executar duas vezes dá o mesmo resultado.

### Gateway exclusivo
`Decisão de crédito?` lê `decisaoCredito`. O fluxo **padrão** (sem condição) vai para revisão humana: se o bureau devolver algo inesperado, a proposta cai na mesa de um analista em vez de virar incidente ou ser aprovada por engano.

### User task (tarefa humana)
`Revisar proposta` é uma Camunda user task com formulário (`revisao-proposta.form`). Ela aparece no Tasklist para quem estiver no grupo `analistas-credito`. O processo fica parado ali pelo tempo que for, sem thread presa nem timeout, porque o estado está no motor.

Dá para completar de dois jeitos:
- pelo Tasklist, preenchendo o formulário;
- pela API `POST /api/propostas/{id}/revisao`, que busca a tarefa (`newUserTaskSearchRequest`) e a completa (`newCompleteUserTaskCommand`).

A busca é eventualmente consistente: a tarefa leva alguns instantes para ser indexada. Enquanto isso, a API responde 409 com `Retry-After`.

### Receive task e mensagem
`Aguardar assinatura` espera a mensagem `contrato-assinado`, correlacionada pela chave `=propostaId`. Quem assina é um sistema externo, simulado por `POST /api/propostas/{id}/assinatura`:

```java
camunda.newPublishMessageCommand()
        .messageName("contrato-assinado")
        .correlationKey(propostaId)
        .messageId("assinatura-" + propostaId)
        .timeToLive(Duration.ofMinutes(10))
```

- **`messageId`**: deduplicação. Uma segunda mensagem com o mesmo id é rejeitada, então reenviar a assinatura não causa nada. É a idempotency key do mundo de mensagens.
- **`timeToLive`**: se a assinatura chegar antes de o processo estar esperando (corrida entre sistemas), o motor guarda a mensagem por 10 minutos e entrega quando a instância chegar na receive task.

### Timer de borda
O relógio na borda de `Aguardar assinatura` dispara depois de `=prazoAssinatura` (padrão `PT2M`, variável por proposta). Ele é **interrompente**: cancela a espera e segue para a compensação. É o "timeout por ausência", que numa saga coreografada não tem dono natural. Aqui o motor cuida dele.

### Compensação
Cada passo compensável tem um evento de compensação na borda, ligado (associação pontilhada) à tarefa que o desfaz, marcada com `isForCompensation="true"`:

```
[Reservar limite] ··· ⟲ ···► [Liberar limite]
[Gerar contrato]  ··· ⟲ ···► [Cancelar contrato]
```

O evento `⟲ Desfazer reserva e contrato` (intermediate throw de compensação) manda o motor executar os handlers de **todas as atividades já concluídas** no escopo. Três detalhes:
- Só compensa o que completou:

  | Onde falhou | Já completados | O que é desfeito |
  |---|---|---|
  | Reservar limite (sem limite) | nenhum | nada (o caminho nem tem throw de compensação) |
  | Gerar contrato (restrição jurídica) | Reservar limite | só Liberar limite: o contrato nunca existiu |
  | Prazo esgotado / desembolso recusado | Reservar limite, Gerar contrato | Liberar limite e Cancelar contrato |

- A compensação não dispara sozinha. Uma falha técnica em qualquer passo vira retry e incidente, sem desfazer nada. Só um caminho que passa por um throw de compensação desfaz.
- Não conte com ordem entre os handlers. Na demo, o cenário 4 cancelou o contrato antes de liberar o limite, e o cenário 5 fez o contrário. Se um desfazer depende do outro, encadeie isso explicitamente no diagrama (por exemplo, um subprocesso de compensação sequencial).
- Os handlers também são jobs com retries. Uma compensação que falha vira incidente, não é esquecida.

No `resiliencia-lab` isso era o método `estornar()` e os estados `COMPENSANDO`/`COMPENSACAO_PENDENTE`, escritos à mão. Aqui está declarado no diagrama.

### Erro BPMN vs falha técnica
Os workers fazem duas coisas diferentes quando algo dá errado. Essa é a distinção mais importante do projeto:

```java
throw CamundaError.bpmnError("DESEMBOLSO_RECUSADO", e.getMessage(), Map.of("motivo", e.getMessage()));
throw CamundaError.jobError(e.getMessage(), null, null, Duration.ofSeconds(3));
```

| | `bpmnError` | `jobError` |
|---|---|---|
| Significa | falha de **negócio**, definitiva (conta não existe, sem limite) | falha **técnica**, passageira (core fora, timeout) |
| O motor faz | segue o caminho do evento de erro na borda | tenta de novo depois do backoff, decrementando `retries` |
| Quando acabam os retries | — | **incidente**: o processo para naquele ponto e espera uma pessoa |

Misturar os dois é o erro clássico: tratar "core fora do ar" como `bpmnError` cancela empréstimos que iam dar certo um minuto depois, e tratar "conta não existe" como `jobError` gasta retries à toa e gera um incidente desnecessário.

### Incidente
Com o core fora, `Desembolsar` falha 3 vezes (`retries="3"`, 3s de intervalo) e vira incidente `JOB_NO_RETRIES`. A instância **não se perde nem é cancelada**: fica parada no desembolso, com reserva e contrato intactos. No Operate, o operador vê a mensagem de erro, corrige a causa e clica em **Retry**.

É a reconciliação humana do `resiliencia-lab`, com uma tela pronta. O teste `coreForaDoArViraIncidenteQueOperadorResolve` faz o mesmo por código (`newUpdateRetriesCommand` e `newResolveIncidentCommand`).

### Task headers
Os quatro "Registrar ..." usam o mesmo job (`registrar-desfecho`) e diferem por um header estático no BPMN (`desfecho=CONTRATADO`, `EXPIRADA`...). O worker lê com `@CustomHeaders`. Um worker serve para vários pontos do diagrama.

## Do código para o motor

| `resiliencia-lab` (à mão) | aqui (Camunda) |
|---|---|
| tabela `transferencia` com status | estado da instância no Zeebe |
| `mudar()` com commit a cada passo | o motor persiste cada passo |
| `RetryPolicy` + backoff | `retries` no BPMN + `jobError` com backoff |
| `estornar()` | eventos e handlers de compensação |
| reconciliação agendada | timer de borda + incidente + Retry no Operate |
| `COMPENSACAO_FALHOU` (precisa de uma pessoa) | incidente, ou user task explícita |
| Idempotency-Key | `messageId` da mensagem + idempotência dos workers |
| `/api/status` | Operate |

O que **não** muda: a idempotência dos sistemas externos e a decisão entre erro de negócio e falha técnica continuam sendo responsabilidade do código. O motor não tem como adivinhar isso.

## Cenários

`scripts/demo.sh` roda os sete:

| # | Entrada | Desfecho | O que mostra |
|---|---|---|---|
| 1 | `cliente-bom`, 10.000 | CONTRATADO | caminho feliz, mensagem correlacionada |
| 2 | `cliente-ruim` | RECUSADA_CREDITO | gateway, nada reservado |
| 3 | `cliente-medio` | CONTRATADO | user task, depois segue |
| 4 | `prazoAssinatura: PT10S` sem assinar | EXPIRADA | timer de borda + compensação |
| 5 | conta `conta-fantasma` | CANCELADA_DESEMBOLSO | `bpmnError` + compensação |
| 6 | `cliente-bom-restricao` | RECUSADA_CONTRATO | erro na borda de Gerar contrato: desfaz só a reserva |
| 7 | core fora do ar | incidente | `jobError`, retries, Retry no Operate |

Regras dos sistemas simulados: nome com `restricao` faz o sistema de contratos recusar a emissão; nome com `ruim` dá score 420 (recusa), `medio` dá 640 (revisão), `bom` dá 820 (aprova). Valor acima de 50.000 sempre vai para revisão. O limite é de 100.000 por cliente, e contas `conta-1` a `conta-3` existem no core.

Chaos: `PUT /chaos` com `{"coreForaDoAr": true, "ativacaoFalhando": false}` e `DELETE /chaos` para desligar.

## API

| Método | Caminho | Para quê |
|---|---|---|
| POST | `/api/propostas` | abre proposta (`cliente`, `valor`, `parcelas`, `contaDestino`, `prazoAssinatura` opcional) |
| GET | `/api/propostas/{id}` | desfecho, efeitos em cada sistema e linha do tempo |
| POST | `/api/propostas/{id}/assinatura` | publica a mensagem de assinatura |
| POST | `/api/propostas/{id}/revisao` | completa a tarefa do analista (`aprovado`, `parecer`) |
| GET | `/api/sistemas` | saldos no core e reservas de limite |
| PUT/DELETE | `/chaos` | liga e desliga falhas |

## Testes

`ContratacaoEmprestimoProcessTest` usa o Camunda Process Test (`@CamundaSpringProcessTest`): sobe um Zeebe num container e roda os workers reais contra ele.

- `CamundaAssert.assertThat(...).hasCompletedElementsInOrder(...)` verifica o caminho percorrido no diagrama.
- `processo.increaseTime(Duration.ofHours(1))` avança o relógio do motor, então o teste de expiração não espera 1 hora.
- São nove casos: os sete cenários, mais limite insuficiente e assinatura duplicada.

## Simplificações

- Os sistemas externos ficam em memória e reiniciar a aplicação apaga tudo. As instâncias no Zeebe sobrevivem (volume do Docker), mas os sistemas simulados não.
- A linha do tempo de `/api/propostas/{id}` é uma conveniência da demo. A fonte de verdade do fluxo é o Operate.
- Não há autenticação: o compose usa `unprotectedApi: true`.
- Versionamento: ao mudar o BPMN e reiniciar, o motor publica uma nova versão. Instâncias em andamento continuam na versão antiga; migrar exige o recurso de *process instance migration* do Operate.
- Licença: verifique os termos atuais do Camunda 8 para uso self-managed em produção.
