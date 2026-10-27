/* Lists Vulkan GPUs as JSON for ReadAlong Desktop: [{"name": ..., "type": 2, "vram_gb": 12.0}].
 * A separate program, run with the system's libraries, because the graphics driver can't load inside the
 * bundled Python app when the bundle's libstdc++ is older than the one the driver was built against. */
#include <stdio.h>
#include <vulkan/vulkan.h>

int main(void) {
    VkInstanceCreateInfo info = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    VkInstance instance;
    if (vkCreateInstance(&info, NULL, &instance) != VK_SUCCESS) {
        printf("[]\n");
        return 0;
    }
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, NULL);
    VkPhysicalDevice devices[16];
    if (count > 16) count = 16;
    vkEnumeratePhysicalDevices(instance, &count, devices);
    printf("[");
    for (uint32_t i = 0; i < count; i++) {
        VkPhysicalDeviceProperties properties;
        VkPhysicalDeviceMemoryProperties memory;
        vkGetPhysicalDeviceProperties(devices[i], &properties);
        vkGetPhysicalDeviceMemoryProperties(devices[i], &memory);
        VkDeviceSize vram = 0;
        for (uint32_t h = 0; h < memory.memoryHeapCount; h++)
            if ((memory.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) && memory.memoryHeaps[h].size > vram)
                vram = memory.memoryHeaps[h].size;
        printf("%s{\"name\": \"", i ? ", " : "");
        for (const char *c = properties.deviceName; *c; c++) {
            if (*c == '"' || *c == '\\') putchar('\\');
            if ((unsigned char)*c >= 0x20) putchar(*c);
        }
        printf("\", \"type\": %d, \"vram_gb\": %.1f}", (int)properties.deviceType, vram / 1073741824.0);
    }
    printf("]\n");
    vkDestroyInstance(instance, NULL);
    return 0;
}
