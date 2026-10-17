# Car Radio

Rádio FM minimalista para a central multimídia **Adak Link8wf** (Android), usando a antena do carro.

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
- Região Brasil por padrão, incluindo a faixa estendida de 76,1 a 87,9 MHz.

## Como o rádio é controlado

O app conversa com o rádio por uma interface única, `Tuner` (no módulo `tuner`), e a tela não sabe qual hardware está por trás. Hoje existem duas implementações:

| Sintonizador | Como funciona | Requisitos |
| --- | --- | --- |
| `Qn8035Tuner` | Fala direto com o chip QN8035 da Link8wf pelo I2C (`i2cget`/`i2cset`), seguindo o datasheet: sintonia, busca por hardware (CCA), sinal e RDS. Procura o chip sozinho em `/dev/i2c-*`. | Root e i2c-tools ou busybox na central. |
| `SimulatedTuner` | Estações fictícias, para usar o app fora do carro. | Nenhum. |

Limitações conhecidas:

- Na maioria das centrais chinesas, quem liga o áudio do rádio nos alto-falantes é o MCU, quando o app de rádio original é aberto. Pelo I2C o app sintoniza o chip, mas pode ser preciso abrir o rádio original uma vez para o MCU trocar a fonte de áudio.
- Apps como o NavRadio+ não usam root: eles falam com o serviço do fabricante (por exemplo `com.syu.ms` nas centrais FYT, ou `android.tw.john.TWUtil` nas TopWay). Quando soubermos qual plataforma a Link8wf usa, esse caminho entra como uma nova implementação de `Tuner`, sem mudar a tela.

## Estrutura

```
tuner/   Kotlin puro, testável sem Android: interface Tuner, QN8035, RDS, simulador
app/     App Android (Jetpack Compose): tela, memórias, ajustes, media session
```

## Compilar

O GitHub Actions compila e testa a cada push (`.github/workflows/build.yml`). Para compilar localmente é preciso o Android SDK com a plataforma 35 e o JDK 17:

```
./gradlew :tuner:test :app:assembleDebug
```
