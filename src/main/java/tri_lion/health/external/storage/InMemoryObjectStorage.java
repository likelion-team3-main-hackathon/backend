package tri_lion.health.external.storage;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test", "dev"})
public class InMemoryObjectStorage implements ObjectStorage {
    private final ConcurrentHashMap<String, byte[]> files = new ConcurrentHashMap<>();

    public void put(String k, byte[] b, String c) {
        files.put(k, b.clone());
    }

    public byte[] get(String k) {
        return files.get(k);
    }

    public void delete(String k) {
        files.remove(k);
    }
}
