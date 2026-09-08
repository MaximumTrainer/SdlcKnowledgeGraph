<script setup lang="ts">
import { ref } from 'vue'

/**
 * A `string[]` property, entered as separate values rather than as one comma-separated string.
 *
 * The distinction matters: the server validates the property as a list, so a control that produced
 * `"a, b"` would be refused as a string where an array was declared, and the user would be told
 * nothing useful about why.
 */
const props = defineProps<{ modelValue: string[]; name: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const draft = ref('')

const add = () => {
  const value = draft.value.trim()
  if (value === '' || props.modelValue.includes(value)) return
  emit('update:modelValue', [...props.modelValue, value])
  draft.value = ''
}

const removeAt = (index: number) =>
  emit(
    'update:modelValue',
    props.modelValue.filter((_, i) => i !== index)
  )
</script>

<template>
  <div class="tag-input" :data-test="`tag-input-${name}`">
    <span v-for="(tag, index) in modelValue" :key="tag" class="tag">
      {{ tag }}
      <button v-if="!disabled" type="button" :aria-label="`Remove ${tag}`" @click="removeAt(index)">
        ×
      </button>
    </span>
    <input
      :id="`field-${name}`"
      v-model="draft"
      :name="name"
      :disabled="disabled"
      type="text"
      placeholder="Add and press Enter"
      @keydown.enter.prevent="add"
      @blur="add"
    />
  </div>
</template>

<style scoped>
.tag-input {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  align-items: center;
  border: 1px solid #cbd5e0;
  border-radius: 4px;
  padding: 0.35rem;
  background: #fff;
}
.tag {
  background: #edf2f7;
  border-radius: 3px;
  padding: 0.1rem 0.4rem;
  font-size: 0.85rem;
}
.tag button {
  border: 0;
  background: none;
  cursor: pointer;
  color: #718096;
}
.tag-input input {
  flex: 1;
  min-width: 8rem;
  border: 0;
  outline: none;
  padding: 0.25rem;
}
</style>
