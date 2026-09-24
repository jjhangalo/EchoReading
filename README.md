# Eco Leitura

Leitor Android pessoal: escreve ou partilha texto, escolhe uma voz local e ouve-o mesmo sem ligação à Internet. Requer Android 13 (API 33) ou superior. Não utiliza contas.

## Utilização

- A voz **Português (Portugal) · Tugão** está incluída na instalação e funciona logo offline.
- **Português (Brasil) · Edresson** e **English (US) · Amy** podem ser descarregadas no selector de vozes; depois funcionam offline. Cada modelo ocupa cerca de 63 MB. Também é possível ajustar a velocidade.
- Em campos de texto de outras aplicações, selecciona texto e escolhe **Ler em voz alta**. A opção depende do menu de selecção da aplicação de origem. O menu **Partilhar** com texto é outro ponto de entrada.
- O painel rápido pode ser fechado enquanto a leitura continua. A notificação disponibiliza reproduzir/pausar, ±10 segundos, parar e repor. No controlo compacto do Android aparecem menos botões.
- Para usar a voz noutras aplicações que aceitam motores TTS do Android, selecciona **Eco Leitura** nas definições de síntese de voz do dispositivo. A notificação do leitor só controla leituras iniciadas dentro do Eco Leitura.

## Desenvolvimento

Requer JDK 21 e Android SDK 36.1. Para compilar e correr os testes locais:

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

O APK de desenvolvimento fica em `app/build/outputs/apk/debug/`. O primeiro arranque pode demorar a preparar os recursos de pronúncia no armazenamento interno. Não existe emulador ou dispositivo ligado neste ambiente, pelo que o funcionamento no telefone ainda precisa de ensaio.

## Recursos de voz

- Runtime [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8), incluindo a API Kotlin e bibliotecas JNI para ARM e x86, em 32 e 64 bits.
- Voz incluída [vits-piper-pt_PT-tugao-medium](https://k2-fsa.github.io/sherpa/onnx/tts/all/Portuguese/vits-piper-pt_PT-tugao-medium.html).
- Vozes opcionais [pt_BR-edresson-low](https://huggingface.co/csukuangfj/vits-piper-pt_BR-edresson-low) e [en_US-amy-low](https://huggingface.co/csukuangfj/vits-piper-en_US-amy-low), fixadas por revisão e checksum no código.

O código de síntese é partilhado entre o leitor e o serviço TTS do sistema. O serviço de leitura usa Media3 para que o áudio e os controlos continuem disponíveis depois de fechar o painel.
