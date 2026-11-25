package de.oliver.fancynpcs;

import de.oliver.fancynpcs.api.AttributeManager;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.v1_21_11.attributes.Attributes_1_21_11;
import de.oliver.fancynpcs.v1_21_5.attributes.Attributes_1_21_5;
import de.oliver.fancynpcs.v1_21_6.attributes.Attributes_1_21_6;
import de.oliver.fancynpcs.v1_21_9.attributes.Attributes_1_21_9;
import de.oliver.fancynpcs.v26_1_1.attributes.Attributes_26_1_1;
import de.oliver.fancynpcs.v26_2.attributes.Attributes_26_2;
import de.oliver.fancynpcs.v26_3.attributes.Attributes_26_3;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AttributeManagerImpl implements AttributeManager {

    private final List<NpcAttribute> attributes;

    public AttributeManagerImpl() {
        attributes = new CopyOnWriteArrayList<>(switch (Bukkit.getMinecraftVersion()) {
            case "26.3" -> Attributes_26_3.getAllAttributes();
            case "26.2" -> Attributes_26_2.getAllAttributes();
            case "26.1.2" -> Attributes_26_1_1.getAllAttributes();
            case "1.21.11" -> Attributes_1_21_11.getAllAttributes();
            case "1.21.9", "1.21.10" -> Attributes_1_21_9.getAllAttributes();
            case "1.21.6", "1.21.7", "1.21.8" -> Attributes_1_21_6.getAllAttributes();
            case "1.21.5" -> Attributes_1_21_5.getAllAttributes();
            default -> List.of();
        });
    }

    @Override
    public NpcAttribute getAttributeByName(EntityType type, String name) {
        for (NpcAttribute attribute : attributes) {
            if (attribute.getTypes().contains(type) && attribute.getName().equalsIgnoreCase(name)) {
                return attribute;
            }
        }

        return null;
    }

    @Override
    public void registerAttribute(NpcAttribute attribute) {
        attributes.add(attribute);
    }

    @Override
    public List<NpcAttribute> getAllAttributes() {
        return attributes;
    }

    @Override
    public List<NpcAttribute> getAllAttributesForEntityType(EntityType type) {
        return attributes.stream()
                .filter(attribute -> attribute.getTypes().contains(type))
                .toList();
    }
}
