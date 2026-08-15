package tri_lion.health.external.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class MinioObjectStorage implements ObjectStorage {
    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorage(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey,
            @Value("${app.storage.private-bucket}") String bucket) {
        this.client =
                MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try (var input = new ByteArrayInputStream(bytes)) {
            client.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(key).stream(
                                    input, bytes.length, -1)
                            .contentType(contentType)
                            .build());
        } catch (Exception exception) {
            throw new IllegalStateException("파일을 Object Storage에 저장하지 못했습니다.", exception);
        }
    }

    @Override
    public byte[] get(String key) {
        try (var input =
                client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return input.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Object Storage 파일을 읽지 못했습니다.", exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw new IllegalStateException("Object Storage 파일을 삭제하지 못했습니다.", exception);
        }
    }
}
