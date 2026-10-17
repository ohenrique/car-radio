# Car Radio

Rádio FM minimalista para a central multimídia **Adak Link8wf** (Android, plataforma FYT com Unisoc UIS8581), usando a antena do carro.

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
- Nas centrais FYT, a faixa e o passo vêm da região de rádio configurada na própria central. Nos outros sintonizadores, Brasil é o padrão, incluindo a faixa estendida de 76,1 a 87,9 MHz.

## Como o rádio é controlado

O app conversa com o rádio por uma interface única, `Tuner` (no módulo `tuner`), e a tela não sabe qual hardware está por trás. Hoje existem três implementações, escolhidas em Ajustes:

| Sintonizador | Como funciona | Requisitos |
| --- | --- | --- |
| `FytTuner` (padrão) | Fala com o serviço de sistema `com.syu.ms` das centrais FYT, o mesmo que o rádio original usa. Faz o MCU trocar a fonte de áudio para o rádio, sintoniza, busca, e recebe frequência, estéreo e RDS do MCU. | Central FYT (UIS7862, UIS8581, UIS7870). Sem root. |
| `Qn8035Tuner` | Fala direto com o chip QN8035 da Link8wf pelo I2C (`i2cget`/`i2cset`), seguindo o datasheet: sintonia, busca por hardware (CCA), sinal e RDS. Procura o chip sozinho em `/dev/i2c-*`. | Root e i2c-tools ou busybox na central. |
| `SimulatedTuner` | Estações fictícias, para usar o app fora do carro. | Nenhum. |

### Protocolo do com.syu.ms

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

Limitações conhecidas:

- O mudo do app é o mudo geral da central. Por isso ele só é usado enquanto o rádio está tocando, e o app o desfaz quando outra fonte assume os alto-falantes. Durante uma ligação o app não tira o som do telefone para o rádio.
- O MCU não informa a intensidade do sinal, então nas centrais FYT o indicador de sinal fica escondido e a busca automática guarda as estações na ordem da faixa.
- Pelo I2C (QN8035) o app sintoniza o chip, mas quem liga o áudio do rádio nos alto-falantes é o MCU, então esse caminho em geral fica mudo nas centrais FYT.

## Estrutura

```
tuner/   Kotlin puro, testável sem Android: interface Tuner, FYT, QN8035, RDS, simulador
app/     App Android (Jetpack Compose): tela, memórias, ajustes, media session, cliente Binder do com.syu.ms
```

## Compilar

O GitHub Actions compila e testa a cada push (`.github/workflows/build.yml`). Para compilar localmente é preciso o Android SDK com a plataforma 35 e o JDK 17:

```
./gradlew :tuner:test :app:assembleDebug
```
