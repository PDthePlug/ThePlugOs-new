const button=document.querySelector('#copy-checksum');
const feedback=document.querySelector('#copy-status');
button.addEventListener('click',async()=>{
  try{await navigator.clipboard.writeText(document.querySelector('#checksum').textContent.trim());feedback.textContent='Checksum copied';}
  catch{feedback.textContent='Select the checksum text to copy it.';}
});
