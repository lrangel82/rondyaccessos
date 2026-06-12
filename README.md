REANUDACIÓN DE PROYECTO: "Rondy Accesos" -

## MANIFIESTO ARQUITECTONICO DE CONTINUACION TOTAL - RONDY ACCESOS## CAPA DE DATOS Y PERSISTENCIA (OFFLINE-FIRST)

1. Capa de Datos Unificada (DataRawRondin.kt): Arquitectura compacta sin dependencias externas. Usa RAM (TableState), persistencia en disco mediante Strings JSON (MySettings) y sincronizacion asincrona con Google Sheets via Service Account (res/raw/). Concurrencia protegida mediante Mutex/Locks atomicos.
2. Catalogos Expandidos (SheetTable): Modelado estricto de enums para las tablas del condominio: VEHICULOS, TAGS, DIRECCIONES, AUTOS_REGISTRADOS, RESIDENTES_UNIDAD, ALARMAS_RONDIN, BITACORA_ACCESOS, EXCEPCIONES, DOMICILIOS_MOROSOS, TERRAZA_RESERVAS, PLACAS_PROHIBIDAS.
3. Roles de Hardware (SateliteMode): Identificacion fisica por hardware (CASETA, INGRESO_VEHICULAR, INGRESO_PEATONAL, SALIDA_VEHICULAR, SALIDA_PEATONAL) alojada en com.larangel.rondy.models.

## CONECTIVIDAD E INYECCION DE DEPENDENCIAS

1. Red Local Distribuida (Ktor Sockets): Puerto central 35420. RondySocketService (Foreground Service en Caseta Padre) corre un TCP Server para JSONs y un UDP Broadcast para Discovery Pings. Los Satelites usan RondySocketClient para autodescubrimiento y envio sincrono.
2. Licenciamiento S3 y Splash: Validacion mediante archivo .ini en Amazon S3 via OkHttpClient y XmlPullParser. Si el campo CODIGO_ACTIVACION esta vacio, suspende la corrutina en mostrarDialogoCapturaHKey mediante un AlertDialog nativo antes de consultar S3. En caso de falla, rutea a LimitedFeatureActivity.
3. Inyeccion de Dependencias de Alto Nivel (RondyApplication): Instanciacion 'by lazy' de DataRawRondin, MySettings, BotCasetaApiService (con convertidor oficial de Kotlinx Serialization) y GeminiVoiceAssistant. Inyeccion obligatoria a ViewModels mediante IngresoVehicularViewModelFactory(application as RondyApplication). Incluye puntero de callback dinamico (registroCallbackActivo) para intercepcion de IA en caliente.

## PIPELINE DE DOBLE CAMARA CONCURRENTE

1. Camara IP Externa de Placas (LibVLC): Streaming decodificado por hardware. Captura de fotogramas asincrona cada 800ms mediante la API PixelCopy desde la GPU (SurfaceView interno de VLCVideoLayout) directo al pool de background de ML Kit Text Recognition con filtro Regex. El stream se apaga de inmediato al obtener la matricula.
2. Camara Frontal de Codigos QR (CameraX): Inicializacion nativa conectando un caso de uso Preview y un ImageAnalysis (STRATEGY_KEEP_ONLY_LATEST) hacia ML Kit Barcode Scanning. Enfriamiento (qrCooldownActivo) estricto de 10 segundos tras detectar un prefijo "ginn".
3. Solucion a Pantallas en Negro (TextureView & Croma VLC): CameraX forzado en app:implementationMode="compatible" (TextureView). LibVLC inicializado con argumentos nativos --vout=android_display, --android-display-chroma=RV32 y --video-wallpaper para evitar el secuestro exclusivo de la GPU. Permisos dinamicos gestionados en el flujo seguro .post {} de la Activity.
4. Manejo de Errores de Red en Camara IP: Intercepcion de Event.EncounteredError en mediaPlayer.setEventListener junto con un coroutine timeout de panico de 5 segundos para mitigar fallas del core C++ de VLC. Muestra el panel gris translucido layoutFixCamaraPlaca en falla.

## AUDIO, INTERFACES E INTELIGENCIA HIBRIDA

1. Manejo de Senales Acusticas e Interrupcion Hibrida (TTS Audio Focus): Ciclos de reinicio de hardware espaciados a 2000ms para eliminar chasquidos (pop). Sincronizacion del microfono mediante UtteranceProgressListener atado al ID "RondySpeechID" en GeminiVoiceAssistant; apaga el microfono en onStart() de la locucion y lo reenciende en onDone(). El indicador visual (indicadorColorMicro) muta en tiempo real via runOnUiThread.
2. Flujo Guiado Seccional contra Bloqueos ANR: Controlado por el enum CaptureStep. Inyeccion dinamica de botones (Chips) desde el .ini de S3 (motivos) y DataRawRondin (direcciones). Redibujado protegido con la bandera estructural ultimoPasoProcesado != state.currentStep para no secuestrar la Input Queue tactil. El microfono se apaga en pasos de teclado manual (Nombre y Placas).
3. Filtros de Voz y Forzado de Idioma (es-MX): SpeechRecognizer blindado con EXTRA_LANGUAGE en 'es-MX'. Filtro de conteo de palabras por Regex "\s+"; si es exactamente 1 palabra, evade la API de Gemini en la nube y ejecuta procesarEntradaVozLocalFallback de forma 100% offline. Temporizador de panico asincrono de 30 segundos refrescado por onUserInteraction(). Regularizacion expres para emergencias (Policia, Ambulancia, Basura) inyectando la calle 'Administracion' con numero '1'.

## UNIFICACION, SEGURIDAD Y GUARDADO TRANSACCIONAL (NUEVAS MEJORAS)

1. Unificacion de DTOs y Guardado Transaccional Final: Eliminacion de duplicidad de objetos de red. El Socket y la Bitacora local comparten la entidad @Serializable data class AccesoBitacora. El guardado evalua en caliente state.status para dictaminar el acceso y despliega un panel de alta visibilidad a pantalla completa en el lado derecho (Verde/Texto Negro para Autorizado, Rojo/Texto Blanco para Denegado) mostrando el detalle guardado en state.descripcionInput. Almacenamiento de imagenes efimero comprimido al 60% en Cloud Storage (retencion automatica de 30 dias) y actualizacion en background de registros historicos abiertos (cierre de fechaSalida por reentrada de placas).
2. Pipeline de Notificaciones Externas WhatsApp: Doble despacho asincrono hacia la API de WhatsApp (Meta Graph API / Render). El primer hilo inyecta las variables del estado actual a los templates estructurados de autorizacion/denegacion, y el segundo adjunta los enlaces de las imagenes de las camaras concurrentes a los numeros registrados para el domicilio en DataRawRondin.
3. Pre-carga Historica y Pipeline de Seguridad Secuencial: Lectura OCR de placas desde el paso 1. Si existe historial, saluda por nombre ("Bienvenido de nuevo X") y activa dialogo de confirmacion: si es UBER/Servicios omite cargar el domicilio previo y pregunta la direccion; si es Visita/Residente pregunta si va al mismo domicilio. Al confirmar direccion, ejecuta el Filtro de Morosidad (denegacion inmediata por voz y panel gigante si el domicilio es moroso), seguido de la Matriz de Excepciones (valida vigencia de fechas, coincidencia de placas/conductor o reglas generales, aplicando un consumo/vencimiento automatico e inmediato si es un uso unico de servicio).

============================================================


Confirma que has leído, comprendido y cargado este manifiesto en tu memoria. Quedo a la espera de mis instrucciones para pulir o refactorizar el siguiente módulo condominal.


---
###### GUIA PARA EL DISENIO DEL ESTILO
PROTOCOLO DE DISEÑO UI/UX: RONDY ACCESOS (CYBER-SECURITY DARK)
Este protocolo define la identidad visual y estructural para todos los módulos del sistema (Ingresos, Salidas, Peatonal, Satélites).
1. FUNDAMENTOS VISUALES (PALETA SEMÁNTICA) 
   - Background Base: #0A0E14 (Deep Midnight Blue).
   - Superficies/Cards: #161B22 (Steel Gray).
   - Bordes/Stroke: #30363D (1dp de grosor para delimitación).
   - Acento/IA: #00D4FF (Electric Cyan). Uso: Botones principales, estados activos, bordes de enfoque.
   - Éxito: #00E676 (Emerald Green). Uso: Autorizaciones, procesos exitosos.
   - Alerta/Timer: #FFAB40 (Soft Amber). Uso: Temporizadores, advertencias leves.
   - Error/Pánico: #FF5252 (Vivid Red). Uso: Denegaciones, fallas de hardware.
   - Texto Secundario: #8B949E (Slate Gray). 
2. COMPONENTES ESTRUCTURALES
   - Contenedores: Uso obligatorio de MaterialCardView con cardCornerRadius="24dp" para interacción y 16dp para visualización (cámaras).
   - Botones:
         - Principales: MaterialButton (Contained), altura 60dp, esquinas 12dp.
         - Secundarios: MaterialButton (TextButton/Outlined) en color #FF5252 para cancelaciones.
   - Selección (Chips): Uso de ChipGroup inyectado dinámicamente. Chips con fondo transparente, borde blanco/cian y texto en blanco (18sp).
   - Entrada de Datos: TextInputLayout estilo OutlinedBox. Fuente en negrita, tamaño 28sp a 34sp.
   - Indicadores LED: El objeto indicadorColorMicro debe usar el drawable shape_circle_glow para simular un LED físico con resplandor. 
3. JERARQUÍA Y UX (EL CAMINO DEL OJO)
     1.Zona Superior (Status): Header minimalista con mensaje de acción actual, Timer de inactividad tipo "Pill" y botón sutil de configuración.
     2.Zona Central (Contexto): Módulos de cámaras (LPR y QR) con botones de configuración flotantes internos (transparencia al 50%). 
     3.Zona Inferior/Derecha (Acción): El "Cerebro" de la UI. Título de instrucción grande (24sp), burbuja de diálogo de IA (bg_ai_bubble) y área de scroll para opciones táctiles. 
4. ESTADOS DE ALTA VISIBILIDAD (PANELES)
   - Splash de Inactividad: Fondo con degradado @drawable/bg_splash_gradient. Debe cubrir el 100% de la pantalla, ser clickable y mostrar un mensaje de bienvenida ("¡HOLA!") en 80sp.
   - Panel de Resultado: Al finalizar un registro, desplegar un FrameLayout a pantalla completa.
         - Autorizado: Fondo verde/negro, texto 64sp negrita.
         - Denegado: Fondo rojo/blanco, texto 64sp negrita.
         - Duración: Mostrar por 4-5 segundos antes del reset automático.
5. REGLAS DE RESPONSIVIDAD (LANDSCAPE)
   - Uso de Guideline al 40% vertical.
   - Lado Izquierdo (40%): Cámaras apiladas verticalmente.
   - Lado Derecho (60%): Panel de interacción completo con ScrollView para garantizar que los botones de acción no salgan del viewport.

Instrucción para la IA: "Al diseñar o refactorizar cualquier layout de Rondy Accesos, aplica estrictamente este protocolo, priorizando la legibilidad para guardias de seguridad y la estética de centro de control moderno."




# Promt para generar dialogos para la animacion
Podrias generar un prompt de un dialogo y de una secuencia de animacion de un clip corto que permita promocionar los aspectos mas imporntates de la aplicacion Rondy.
