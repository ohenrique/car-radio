# Car Radio

Rádio FM minimalista para a central multimídia **Adak Link8wf**, usando a antena do carro. A Link8wf é uma central **NWD K4811** (Unisoc UIS8581, Android 10 de verdade, embora os ajustes mostrem "Android 15"; build `K4811_NWD_S212851`), e não FYT como se pensava antes. O app também funciona em centrais FYT.

## Instalar

Baixe o `car-radio.apk` da release [Versão de teste](../../releases/tag/teste), que é atualizada a cada mudança no `main`, e instale na central (permita "fontes desconhecidas"). Todos os APKs são assinados com a mesma chave (`app/debug.keystore`), então cada versão instala por cima da anterior.

## O que já faz

- Sintonia manual pelas setas (segure para repetir) ou arrastando o dial.
- Busca de estações para frente e para trás.
- Busca automática, que preenche as memórias com as estações mais fortes.
- 18 memórias em FM1, FM2 e FM3. Toque numa memória vazia para salvar a estação atual; segure para salvar por cima ou apagar.
- LOC/DX, estéreo ou mono, e RDS (nome da rádio e texto).
- Volta para a última estação ao abrir.
- Botões do volante e controles na notificação, por media session.
- Nas centrais NWD e FYT, a faixa e o passo vêm da região de rádio configurada na própria central. Nos outros sintonizadores, Brasil é o padrão, incluindo a faixa estendida de 76,1 a 87,9 MHz.

## Como o rádio é controlado

O app conversa com o rádio por uma interface única, `Tuner` (no módulo `tuner`), e a tela não sabe qual hardware está por trás. Hoje existem quatro implementações, escolhidas em Ajustes. O padrão, **Automático**, escolhe sozinho cada vez que o rádio liga: NWD quando a central tem o serviço `com.nwd.radio.service`, senão FYT quando tem o `com.syu.ms`, senão mostra um erro que aponta para os ajustes e o Diagnóstico.

| Sintonizador | Como funciona | Requisitos |
| --- | --- | --- |
| `NwdTuner` | Fala com o serviço de sistema `com.nwd.radio.service` das centrais NWD, o mesmo que o rádio original usa, e pede ao `com.nwd.kernel` que o MCU ponha o rádio nos alto-falantes. Sintoniza, busca, e lê frequência, estéreo, RDS, LOC, fonte e mudo. | Central NWD (K4811, K2501...). Sem root. |
| `FytTuner` | Fala com o serviço de sistema `com.syu.ms` das centrais FYT, o mesmo que o rádio original usa. Faz o MCU trocar a fonte de áudio para o rádio, sintoniza, busca, e recebe frequência, estéreo e RDS do MCU. | Central FYT (UIS7862, UIS8581, UIS7870). Sem root. |
| `Qn8035Tuner` | Fala direto com um chip QN8035 pelo I2C (`i2cget`/`i2cset`), seguindo o datasheet: sintonia, busca por hardware (CCA), sinal e RDS. Procura o chip sozinho em `/dev/i2c-*`. | Root e i2c-tools ou busybox na central. |
| `SimulatedTuner` | Estações fictícias, para usar o app fora do carro. | Nenhum. |

Quem atualiza de uma versão anterior e tinha FYT ou QN8035 escolhido passa para Automático uma vez; o Simulador fica como estava.

### Protocolo NWD

O rádio fica atrás do MCU, e o MCU também decide qual fonte chega aos alto-falantes: o rádio sintoniza em silêncio até o `com.nwd.kernel` trocar a fonte para ele. As chamadas são Binder cru (`app/.../nwd/NwdServiceClient.kt`), sem classes do fabricante e sem permissões.

O app se liga ao serviço pela ação `com.nwd.radio.service.ACTION_RADIO_SERVICE` (componente `com.nwd.radio.service/.RadioService`) e confere que o binder é `com.nwd.radio.service.RadioFeature`. Nenhuma chamada da interface é `oneway`, então todas esperam a resposta. Booleanos vão como int 0/1, bytes com `writeByte`.

| Código | Chamada | Para quê |
| --- | --- | --- |
| 1 | `setCurrentFrequency(int freq, byte banda, int memória)` | Sintonizar; a memória é sempre 0 (outro valor sintoniza uma memória do próprio serviço) |
| 2 | `getCurrentFrequency()` → `Frequency` | Confirmar a sintonia e acompanhar o dial |
| 3 / 4 | `seek(boolean)` / `search(boolean)` | Buscar; o app descobre qual dos dois para na próxima estação (o outro anda um canal) e para que lado vai o booleano |
| 5 | `changeBand()` | Voltar de AM para FM, se o comando direto não bastar |
| 8 / 9 | `setNearOn` / `isNearOn` | LOC/DX |
| 10 | `isHasStrero()` | Indicador ST (piloto estéreo) |
| 11 / 12 | `setStreroOn` / `isStreroOn` | Mono, só depois que o usuário pede mono uma vez |
| 22 | `getRadioPoint()` → `RadioPoint[]` | Faixa, passo e unidade do FM |
| 23 | `getRadioState()` → byte | 1 = pronto, 2 a 4 = buscando |
| 24 / 25 | `registCallback` / `unRegistCallback` | Avisos do serviço |
| 27 | `sendRadioCommand(14, 1)` | Escolher FM1 direto |
| 28 | `getRtMessage()` → String | Texto RDS |
| 29 | `getRadioType()` → int | Tipo do rádio: 0 = no MCU (o caso da K4811, pelo que se sabe), 1 ARM, 2 Allwinner, 3 FM do SoC |

O Diagnóstico também lê 14, 16, 18, 21 e 30. Formatos: `Frequency` = byte banda, String nome (PS), int frequência; `RadioPoint` = int mínimo, int máximo, int passo, com [0] = FM e [1] = AM; uma lista vem como um int de tamanho (-1 = nula) e, antes de cada item, um int que diz se ele existe. Bandas: 0 a 2 = FM1 a FM3, 3 a 5 = AM. Na K4811 o FM vem em unidades de 10 kHz (8750 = 87,50 MHz) e o AM em kHz; o app tira a unidade do `getRadioPoint` (e aceita 100 kHz e kHz, como em outras NWD).

O callback (`com.nwd.radio.service.RadioCallback`, códigos 1 a 14, todos de ida e volta) avisa estado (1), frequência (2: byte banda, int frequência, String nome, int memória), LOC (3), piloto estéreo (4), faixa (10), texto RDS (13) e varredura (14). Esses avisos nem sempre chegam, então o app também consulta o serviço a cada segundo.

Fonte e mudo, por avisos (broadcasts) do Android:

| Aviso | Extras (tipo exato) | Quando |
| --- | --- | --- |
| `com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE`, para o pacote `com.nwd.kernel` | `extra_source_id` byte 4 (o receptor lê um byte e ignora int) | Pôr o rádio nos alto-falantes: primeiro passo no tipo 0 ou desconhecido |
| `com.nwd.action.ACTION_APP_IN_OUT` | `extra_app_id` int 8, `extra_app_operation` int 1, `extra_app_event` int 0 | Segundo passo no tipo 0 ou desconhecido, primeiro nos outros |
| `com.nwd.ACTION_MEDIA_PLAY` | `extra_app_id` int 8 | Logo depois do anterior, só quando o tipo é maior que 0 (rádio fora do MCU) |
| `com.nwd.action.ACTION_SET_MUTE` | `extra_mute` boolean | Mudo |

Depois de cada passo o app espera até 3 s que `mcu_current_source` vire 4, e nunca repete um pedido em menos de 3 s (repetir cedo demais pode iniciar o rádio duas vezes). Ele lê de `Settings.System`, e acompanha com observadores, `mcu_current_source` (0 Android, 4 rádio, 7 AUX, 8 DVR), `mcu_mute_state` (diferente de 0 = mudo) e `mcu_state` (1 = acordado; a central desmonta o FM ao dormir). Também ouve `com.nwd.action.ACTION_SEND_RADIO_FREQUENCE_NEW` (`extra_frequence`, `extra_band`, `extra_ps_name`), `com.nwd.action.ACTION_CHANGE_SOURCE` (`extra_source_id`), `com.nwd.ACTION_MUTE_STATE_CHANGE` (`extra_mute_state`) e `com.nwd.action.ACTION_MCU_STATE_CHANGE` (`extra_mcu_state`).

O app nunca escreve em `Settings`, não manda quadros crus ao MCU, não chama o `com.nwd.setting.service`, o `com.nwd.factory.setting` (um serviço dele pode apagar a central) nem a classe `com.nwd.app.NwdFmManager`, e não usa AMS, INTRO, as memórias do serviço nem os ajustes de RDS, AF e TA do serviço.

Fontes. Destes projetos só foram usados fatos de interoperabilidade (nomes, códigos de transação, ordem dos campos, ações, extras e comportamento observado); nenhum código, arquivo AIDL ou comentário foi copiado, e nada vem do NavRadio+:

- [garagemdomadeira/gdm-launcher-k4811](https://github.com/garagemdomadeira/gdm-launcher-k4811) (GPL-3.0), launcher que roda na K4811: códigos do RadioFeature e do callback, `APP_IN_OUT` e `MEDIA_PLAY` na K4811, FM em 10 kHz, `sendRadioCommand(14, 1)`, `ACTION_SET_MUTE`, os avisos antigos que chegam até 1,5 s depois de uma sintonia, e o reenvio da estação ao ligar.
- [speghetimafia/calm-launcher](https://github.com/speghetimafia/calm-launcher) (MIT), launcher para a NWD K2501: `REQUEST_CHANGE_SOURCE` com byte 4, as chaves `mcu_*`, os avisos `FREQUENCE_NEW` e `MCU_STATE_CHANGE`, FM em 100 kHz em outras NWD.
- [ninthfreak/Carnyx](https://github.com/ninthfreak/Carnyx) (GPL-3.0), app para NWD com Allwinner, com notas do autor sobre o bytecode do fabricante: códigos conferidos no bytecode, ordem dos campos de `Frequency`, callback de ida e volta, avisos que nem sempre chegam, `search` como busca de estação e `seek` como um canal, `mcu_state`.
- [kapi21/OpenRadioFM](https://github.com/kapi21/OpenRadioFM) (Apache-2.0), rádio para a NWD G5 (Qualcomm): a tabela de códigos, o serviço frio depois de reiniciar, as chaves `nwd_radio_*` lidas no Diagnóstico.
- [edman01/radio-plus-open](https://github.com/edman01/radio-plus-open) (MIT), leitura estática do mesmo build da Link8wf (RadioService 2.3.0, KernelService 2.5.0): tipo 0 (MCU), `REQUEST_CHANGE_SOURCE` entregue ao `com.nwd.kernel`, estado 1 = pronto, a banda 5, o intervalo entre trocas de banda, e os hashes dos APKs que o Diagnóstico compara com os da central.

### Protocolo do com.syu.ms (FYT)

O app se liga ao serviço com a ação `com.syu.ms.toolkit` no pacote `com.syu.ms` e pede um módulo por código (0 principal, 1 rádio, 4 som). Cada módulo aceita comandos `cmd(código, ints)` e envia atualizações para quem se registrou. As chamadas são Binder cru (`app/.../fyt/SyuToolkitClient.kt`), sem classes do fabricante.

| O quê | Comando |
| --- | --- |
| Pôr o rádio nos alto-falantes | principal `0` com `[1]` (algumas versões usam `[11]`) |
| Tirar e pôr o mudo | som `0` com `[-3]` / `[-4]` |
| Subir ou descer um canal | rádio `3` / `4` |
| Buscar para cima ou para baixo | rádio `5` / `6` |
| Sintonizar direto | rádio `13` com `[1, frequência]` (ainda não confirmado; se não funcionar, o app sobe ou desce canal por canal) |

Atualizações usadas: frequência (rádio `1`), faixa (`16`, `[mín, máx, passo, canais]`), estéreo (`21`), estado da busca (`22`), LOC (`23`), nome da rádio (`14` e `26`), texto RDS (`13`), fonte ativa (principal `0`) e mudo (som `3`). Nas UIS7862/8581 o FM vem em unidades de 10 kHz (8750 = 87,50 MHz); o app detecta a unidade pela faixa que o MCU informa.

Fontes: o launcher FYT descompilado em [vasyl91/FYT-Launcher-Mod](https://github.com/vasyl91/FYT-Launcher-Mod) (classes `com.syu.ipc`) e o app aberto [PimpinPumpkin/FytRadio](https://github.com/PimpinPumpkin/FytRadio). Nenhum código do NavRadio+ foi usado.

## Limitações conhecidas

- O mudo do app é o mudo geral da central, nas NWD e nas FYT. Por isso ele só é usado enquanto o rádio está tocando, e o app o desfaz quando outra fonte assume os alto-falantes. Durante uma ligação o app não tira o som do telefone para o rádio.
- O MCU não informa a intensidade do sinal, então nas centrais NWD e FYT o indicador de sinal fica escondido e a busca automática guarda as estações na ordem da faixa.
- Pelo I2C (QN8035) o app sintoniza o chip, mas quem liga o áudio do rádio nos alto-falantes é o MCU, então esse caminho em geral fica mudo nessas centrais.
- O sintonizador NWD ainda não foi testado na Link8wf. Várias coisas só a central responde, e o app as descobre em uso e anota cada decisão em Ajustes → Diagnóstico → "Registro do rádio NWD": qual pedido troca a fonte e quanto demora, se a primeira sintonia é ignorada, qual chamada é a busca de estação e para que lado vai, se o `ACTION_SET_MUTE` funciona, se o RDS vem preenchido.
- Nas NWD a ligação telefônica só é percebida pelo modo de áudio do Android. Se o telefone da central não o mudar, mexer no rádio durante uma ligação pode pedir os alto-falantes para ele.
- Não há comando seguro para parar uma busca na NWD: uma busca cancelada termina sozinha, e a próxima sintonia espera por ela.
- Se o rádio ainda for a fonte quando a central desliga, ela abre o rádio original ao ligar de novo.
- Nas NWD e FYT o app não pede o foco de áudio do Android (a NWD fecha apps que o seguram quando a fonte sai do Android) e ignora o botão de parar da media session, que o firmware da NWD manda ao trocar a fonte para o rádio. Os botões do volante que a própria central também trata podem agir duas vezes.

## Estrutura

```
tuner/   Kotlin puro, testável sem Android: interface Tuner, NWD (tuner/nwd), FYT, QN8035, RDS, simulador
app/     App Android (Jetpack Compose): tela, memórias, ajustes, media session, diagnóstico, clientes Binder
         do com.nwd.radio.service (app/nwd, com os avisos e as chaves mcu_*) e do com.syu.ms (app/fyt)
```

## Compilar

O GitHub Actions compila e testa a cada push (`.github/workflows/build.yml`). Para compilar localmente é preciso o Android SDK com a plataforma 35 e o JDK 17:

```
./gradlew :tuner:test :app:assembleDebug
```
