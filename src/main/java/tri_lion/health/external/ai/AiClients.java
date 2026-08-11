package tri_lion.health.external.ai;
public final class AiClients {private AiClients(){} public interface OcrClient{String extract(byte[] file);} public interface LlmClient{String healthAnalysis(String input);String coaching(String input);} }
